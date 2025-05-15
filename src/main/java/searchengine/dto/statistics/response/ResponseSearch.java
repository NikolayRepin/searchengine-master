package searchengine.dto.statistics.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor(force = true)
public class ResponseSearch extends ResponseBoolean{
    Integer count;
    List<SearchResult> data;

    public ResponseSearch(boolean result, Integer count, List<SearchResult> data) {
        super(result);
        this.count = count;
        this.data = data;
    }

    public ResponseSearch(Integer count, List<SearchResult> data) {
        this.count = count;
        this.data = data;
    }
}





