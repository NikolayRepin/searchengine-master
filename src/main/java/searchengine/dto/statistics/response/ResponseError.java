package searchengine.dto.statistics.response;

import lombok.*;

@Data
@NoArgsConstructor(force = true)
public class ResponseError extends ResponseBoolean{
    public ResponseError(boolean result, String message) {
        super(result);
        this.message = message;
    }

    public ResponseError(String message) {
        this.message = message;
    }

    String message;
}
