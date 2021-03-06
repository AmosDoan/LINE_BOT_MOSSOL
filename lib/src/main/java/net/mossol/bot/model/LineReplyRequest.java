package net.mossol.bot.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Created by Amos.Doan.Mac on 2017. 12. 3..
 */
@Data
public class LineReplyRequest {
    private final String replyToken;
    private List<LineMessage> messages = new ArrayList<>();

    public void setMessage(LineMessage message) {
        messages.add(message);
    }
}
