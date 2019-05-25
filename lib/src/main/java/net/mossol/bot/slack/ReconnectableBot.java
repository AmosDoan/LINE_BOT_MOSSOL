package net.mossol.bot.slack;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import me.ramswaroop.jbot.core.common.BaseBot;
import me.ramswaroop.jbot.core.common.BotWebSocketHandler;
import me.ramswaroop.jbot.core.common.Controller;
import me.ramswaroop.jbot.core.common.EventType;
import me.ramswaroop.jbot.core.slack.Bot;
import me.ramswaroop.jbot.core.slack.SlackService;
import me.ramswaroop.jbot.core.slack.models.Event;
import me.ramswaroop.jbot.core.slack.models.Message;

public abstract class ReconnectableBot extends BaseBot {

    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    @Nullable
    protected WebSocketConnectionManager manager;

    /**
     * Service to access Slack APIs.
     */
    @Autowired
    protected SlackService slackService;

    /**
     * Task to ping Slack at regular intervals to prevent
     * closing of web socket connection.
     */
    private PingTask pingTask;

    /**
     * Class extending this must implement this as it's
     * required to make the initial RTM.start() call.
     *
     * @return the slack token of the bot
     */
    public abstract String getSlackToken();

    /**
     * An instance of the Bot is required by
     * the {@link BotWebSocketHandler} class.
     *
     * @return the Bot instance overriding this method
     */
    public abstract ReconnectableBot getSlackBot();

    /**
     * Invoked after a successful web socket connection is
     * established. You can override this method in the child classes.
     *
     * @param session websocket session between bot and slack
     * @see WebSocketHandler#afterConnectionEstablished
     */
    public void afterConnectionEstablished(WebSocketSession session) {
        logger.debug("WebSocket connected: {}", session);
    }

    /**
     * Invoked after the web socket connection is closed.
     * You can override this method in the child classes.
     *
     * @param session websocket session between bot and slack
     * @param status  websocket close status
     * @see WebSocketHandler#afterConnectionClosed
     */
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        logger.info("WebSocket closed: {}, Close Status: {}", session, status.toString());
        startRTMAndWebSocketConnection();
    }

    /**
     * Handle an error from the underlying WebSocket message transport.
     *
     * @param session   websocket session between bot and slack
     * @param exception thrown because of transport error
     * @see WebSocketHandler#handleTransportError
     */
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        logger.error("Transport Error: ", exception);
    }

    /**
     * Invoked when a new Slack event(WebSocket text message) arrives.
     *
     * @param session     websocket session between bot and slack
     * @param textMessage websocket message received from slack
     */
    public final void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        ObjectMapper mapper = new ObjectMapper();
        logger.debug("Response from Slack: {}", textMessage.getPayload());
        try {
            Event event = mapper.readValue(textMessage.getPayload(), Event.class);
            if (event.getType() != null) {
                if (event.getType().equalsIgnoreCase(EventType.IM_OPEN.name())
                    || event.getType().equalsIgnoreCase(EventType.IM_CREATED.name())) {
                    if (event.getChannelId() != null) {
                        slackService.addImChannelId(event.getChannelId());
                    } else if (event.getChannel() != null) {
                        slackService.addImChannelId(event.getChannel().getId());
                    }
                } else if (event.getType().equalsIgnoreCase(EventType.MESSAGE.name())) {
                    if (event.getText() != null && event.getText().contains(
                            slackService.getCurrentUser().getId())) { // direct mention
                        event.setType(EventType.DIRECT_MENTION.name());
                    } else if (slackService.getImChannelIds().contains(
                            event.getChannelId())) { // direct message
                        event.setType(EventType.DIRECT_MESSAGE.name());
                    }
                } else if (event.getType().equalsIgnoreCase(EventType.HELLO.name())) {
                    pingTask = new PingTask(session);
                    pingAtRegularIntervals();
                }
            } else { // slack does not send any TYPE for acknowledgement messages
                event.setType(EventType.ACK.name());
            }

            if (isConversationOn(event)) {
                invokeChainedMethod(session, event);
            } else {
                invokeMethods(session, event);
            }
        } catch (Exception e) {
            logger.error("Error handling response from Slack: {} \nException: ", textMessage.getPayload(), e);
        }
    }

    /**
     * Method to send a reply back to Slack after receiving an {@link Event}.
     * Learn <a href="https://api.slack.com/rtm">more on sending responses to Slack.</a>
     *
     * @param session websocket session between bot and slack
     * @param event   received from slack
     * @param reply   the message to send to slack
     */
    protected final void reply(WebSocketSession session, Event event, Message reply) {
        try {
            if (StringUtils.isEmpty(reply.getType())) {
                reply.setType(EventType.MESSAGE.name().toLowerCase());
            }
            reply.setText(encode(reply.getText()));
            if (reply.getChannel() == null && event.getChannelId() != null) {
                reply.setChannel(event.getChannelId());
            }

            final String threadTs = event.getThreadTs();
            if (!StringUtils.isEmpty(threadTs)) {
                reply.setThreadTs(threadTs);
            }

            session.sendMessage(new TextMessage(reply.toJSONString()));
            if (logger.isDebugEnabled()) {  // For debugging purpose only
                logger.debug("Reply (Message): {}", reply.toJSONString());
            }
        } catch (IOException e) {
            logger.error("Error sending event: {}. Exception: {}", event.getText(), e.getMessage());
        }
    }

    protected final void reply(WebSocketSession session, Event event, String text) {
        reply(session, event, new Message(text));
    }

    /**
     * Call this method to start a conversation.
     *
     * @param event received from slack
     */
    protected final void startConversation(Event event, String methodName) {
        startConversation(event.getChannelId(), methodName);
    }

    /**
     * Call this method to jump to the next method in a conversation.
     *
     * @param event received from slack
     */
    protected final void nextConversation(Event event) {
        nextConversation(event.getChannelId());
    }

    /**
     * Call this method to stop the end the conversation.
     *
     * @param event received from slack
     */
    protected final void stopConversation(Event event) {
        stopConversation(event.getChannelId());
    }

    /**
     * Check whether a conversation is up in a particular slack channel.
     *
     * @param event received from slack
     * @return true if a conversation is on, false otherwise.
     */
    protected final boolean isConversationOn(Event event) {
        return isConversationOn(event.getChannelId());
    }

    /**
     * Invoke the methods with matching {@link Controller#events()}
     * and {@link Controller#pattern()} in events received from Slack.
     *
     * @param session websocket session between bot and slack
     * @param event   received from slack
     */
    private void invokeMethods(WebSocketSession session, Event event) {
        try {
            List<MethodWrapper> methodWrappers = eventToMethodsMap.get(event.getType().toUpperCase());
            if (methodWrappers == null) { return; }

            methodWrappers = new ArrayList<>(methodWrappers);
            MethodWrapper matchedMethod = getMethodWithMatchingPatternAndFilterUnmatchedMethods(event.getText(),
                                                                                                methodWrappers);
            if (matchedMethod != null) {
                methodWrappers = new ArrayList<>();
                methodWrappers.add(matchedMethod);
            }

            for (MethodWrapper methodWrapper : methodWrappers) {
                Method method = methodWrapper.getMethod();
                if (Arrays.asList(method.getParameterTypes()).contains(Matcher.class)) {
                    method.invoke(this, session, event, methodWrapper.getMatcher());
                } else {
                    method.invoke(this, session, event);
                }
            }
        } catch (Exception e) {
            logger.error("Error invoking controller: ", e);
        }
    }

    /**
     * Invoke the appropriate method in a conversation.
     *
     * @param session websocket session between bot and slack
     * @param event   received from slack
     */
    private void invokeChainedMethod(WebSocketSession session, Event event) {
        Queue<MethodWrapper> queue = conversationQueueMap.get(event.getChannelId());

        if (queue != null && !queue.isEmpty()) {
            MethodWrapper methodWrapper = queue.peek();

            try {
                EventType[] eventTypes = methodWrapper.getMethod().getAnnotation(Controller.class).events();
                for (EventType eventType : eventTypes) {
                    if (eventType.name().equalsIgnoreCase(event.getType())) {
                        methodWrapper.getMethod().invoke(this, session, event);
                        return;
                    }
                }
            } catch (Exception e) {
                logger.error("Error invoking chained method: ", e);
            }
        }
    }

    /**
     * Encode the text before sending to Slack.
     * Learn <a href="https://api.slack.com/docs/formatting">more on message formatting in Slack</a>
     *
     * @param message to encode
     * @return encoded message
     */
    private String encode(String message) {
        return message == null ? null : message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private StandardWebSocketClient client() {
        return new StandardWebSocketClient();
    }

    private AbstractWebSocketHandler handler() {
        return new ReconnectableBotWebSocketHandler(getSlackBot());
    }

    /**
     * Entry point where the web socket connection starts
     * and after which your bot becomes live.
     */
    @PostConstruct
    protected void startRTMAndWebSocketConnection() {
        slackService.connectRTM(getSlackToken());
        if (slackService.getWebSocketUrl() != null) {
            if (manager != null) {
                manager.stop();
                manager = null;
            }
            manager = new WebSocketConnectionManager(client(), handler(),
                                                     slackService.getWebSocketUrl());
            manager.start();
        } else {
            logger.error("No web socket url returned by Slack.");
        }
    }

    private void pingAtRegularIntervals() {
        if (pingTask != null && !pingTask.isRunning()) {
            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            scheduledExecutorService.scheduleAtFixedRate(pingTask, 1L, 30L, TimeUnit.SECONDS);
        }
    }

    class PingTask implements Runnable {

        WebSocketSession webSocketSession;
        boolean isRunning;

        PingTask(WebSocketSession webSocketSession) {
            this.webSocketSession = webSocketSession;
        }

        @Override
        public void run() {
            try {
                logger.debug("Pinging Slack...");
                isRunning = true;
                Message message = new Message();
                message.setType(EventType.PING.name().toLowerCase());
                webSocketSession.sendMessage(new TextMessage(message.toJSONString()));
            } catch (Exception e) {
                logger.error("Error pinging Slack. Slack bot may go offline when not active. Exception: ", e);
            }
        }

        boolean isRunning() {
            return isRunning;
        }
    }
}