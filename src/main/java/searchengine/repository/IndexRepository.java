package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.util.List;
import java.util.Set;

@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {
    List<Index> findByLemma(Lemma lemma);
    List<Index> findByPageInAndLemmaIn(Set<Page> resultPages, Set<Lemma> filteredLemma);
}
