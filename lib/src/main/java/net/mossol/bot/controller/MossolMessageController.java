package net.mossol.bot.controller;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.mossol.bot.connection.ToLocationInfoRequestConverter;
import net.mossol.bot.model.LocationInfo;
import net.mossol.bot.model.ReplyMessage;
import net.mossol.bot.model.TextType;
import net.mossol.bot.repository.LocationInfoMongoDBRepository;
import net.mossol.bot.service.MessageHandler;
import net.mossol.bot.util.MessageBuildUtil;
import net.mossol.bot.util.MossolJsonUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestObject;

@Service
public class MossolMessageController {
    private static final Logger logger = LoggerFactory.getLogger(MossolMessageController.class);

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private MessageHandler messageHandler;

    @Resource
    private LocationInfoMongoDBRepository locationInfoMongoDBRepository;

    @Get("/location")
    public HttpResponse getAllLocations() {
        final List<LocationInfo> locationInfos = locationInfoMongoDBRepository.findAll();
        final String locationInfoStr;
        logger.info("Fetch LocationInfo : <{}>", locationInfos);
        try {
            locationInfoStr = objectMapper.writeValueAsString(locationInfos);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to convert <{}> to Json string", locationInfos);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, locationInfoStr);
    }

    @Post("/location")
    @RequestConverter(ToLocationInfoRequestConverter.class)
    public HttpResponse addLocation(LocationInfo location) {
        LocationInfo locationInfoToAdd = new LocationInfo(location.getTitle(),
                                                          location.getLatitude(),
                                                          location.getLongitude());
        LocationInfo ret = locationInfoMongoDBRepository.insert(locationInfoToAdd);
        logger.debug("add location; id <{}> locationInfo <{}> ", ret.getId(), ret);
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"id \" : \"%s\" }", ret.getId());
    }

    @Put("/location/{id}")
    @RequestConverter(ToLocationInfoRequestConverter.class)
    public HttpResponse updateLocation(@Param("id") String id, LocationInfo location) {
        logger.debug("update location; id <{}> locationInfo <{}> ", id, location);

        if (locationInfoMongoDBRepository.existsById(id)) {
            locationInfoMongoDBRepository.save(location);
            return HttpResponse.of(HttpStatus.OK);
        }

        return HttpResponse.of(HttpStatus.NOT_FOUND);
    }

    @Delete("/location/{id}")
    @RequestConverter(ToLocationInfoRequestConverter.class)
    public HttpResponse deleteLocation(@Param("id") String id, LocationInfo location) {
        logger.debug("delete location; id <{}> locationInfo <{}> ", id, location);
        locationInfoMongoDBRepository.deleteById(id);
        return HttpResponse.of(HttpStatus.OK);
    }

    @Post("/getMessage")
    public HttpResponse getMessage(@RequestObject JsonNode request) {
        final String message = request.get("message").textValue();
        final Map<String, String> ret = new HashMap<>();
        HttpResponse httpResponse;

        logger.info("request {}", request);

        ReplyMessage replyMessage;
        try {
            replyMessage = messageHandler.replyMessage(message);
            if (replyMessage == null) {
                logger.debug("INFO: there is no matching reply message");
                throw new Exception();
            }
        } catch (Exception e) {
            httpResponse = HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON_UTF_8, MossolJsonUtil
                    .writeJsonString(Collections.emptyMap()));
            logger.debug("httpResponse <{}>", httpResponse);
            return httpResponse;
        }

        TextType type = replyMessage.getType();

        String response;
        switch(type) {
            case SELECT_MENU_K:
            case SELECT_MENU_J:
            case SELECT_MENU_D:
                final String foodMessage = MessageBuildUtil.sendFoodMessage(replyMessage.getLocationInfo());
                ret.put("message", foodMessage);
                response = MossolJsonUtil.writeJsonString(ret);
                httpResponse = HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, response);
                return httpResponse;
            case LEAVE_CHAT:
                break;
            default:
                ret.put("message", replyMessage.getText());
                response = MossolJsonUtil.writeJsonString(ret);
                httpResponse = HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, response);
                return httpResponse;
        }

        httpResponse = HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        return httpResponse;
    }
}
