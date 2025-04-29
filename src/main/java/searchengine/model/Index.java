package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Entity
@Table(name = "index")
@Getter
@Setter
public class Index {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne
    @JoinColumn(name = "page_id")
    private Page pageId;

    @ManyToOne
    @JoinColumn(name = "lemma_id")
    private Lemma lemmaId;

    @Column(name = "rank")
    public float rank;
}
