package searchengine.dto.statistics.response;

import lombok.*;

@Data
@NoArgsConstructor(force = true)
public class ResponseError extends ResponseBoolean{
    public ResponseError(boolean result, String error) {
        super(result);
        this.error = error;
    }

    public ResponseError(String error) {
        this.error = error;
    }

    String error;
}
