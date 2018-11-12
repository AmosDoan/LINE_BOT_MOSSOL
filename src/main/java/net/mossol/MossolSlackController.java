package net.mossol;

import me.ramswaroop.jbot.core.slack.Bot;
import me.ramswaroop.jbot.core.slack.Controller;
import me.ramswaroop.jbot.core.slack.EventType;
import me.ramswaroop.jbot.core.slack.models.Event;
import me.ramswaroop.jbot.core.slack.models.Message;
import net.mossol.model.MenuInfo;
import net.mossol.model.ReplyMessage;
import net.mossol.model.TextType;
import net.mossol.service.MenuServiceHandler;
import net.mossol.service.MessageHandler;
import net.mossol.util.MessageBuildUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.Resource;

@Service
public class MossolSlackController extends Bot {

    private static final Logger logger = LoggerFactory.getLogger(MossolSlackController.class);

    @Resource
    private MessageHandler messageHandler;

    @Value("${slackBotToken}")
    private String slackToken;

    @Override
    public String getSlackToken() {
        return slackToken;
    }

    @Override
    public Bot getSlackBot() {
        return this;
    }

    private void handleMessage(WebSocketSession session, Event event) throws Exception {
        ReplyMessage replyMessage = messageHandler.replyMessage(event.getText());
        TextType type = replyMessage.getType();

        switch(type) {
            case SHOW_MENU_K:
            case SHOW_MENU_J:
            case SHOW_MENU_D:
            case ADD_MENU_K:
            case ADD_MENU_J:
            case ADD_MENU_D:
            case DEL_MENU_K:
            case DEL_MENU_J:
            case DEL_MENU_D:
            case TEXT:
                reply(session, event, new Message(replyMessage.getText()));
                return;
            case SELECT_MENU_K:
            case SELECT_MENU_J:
            case SELECT_MENU_D:
                reply(session, event,
                      new Message(MessageBuildUtil.sendFoodMessage(replyMessage.getMenuInfo())));
                return;
            case LEAVE_ROOM:
                break;
        }

        throw new Exception("Send message failed");
    }

    @Controller(events = EventType.MESSAGE)
    public void onReceiveMessage(WebSocketSession session, Event event) {
        try {
            handleMessage(session, event);
        } catch (Exception e) {
            logger.warn("CTRL:SLACK exception is occurred", e);
        }
    }
}