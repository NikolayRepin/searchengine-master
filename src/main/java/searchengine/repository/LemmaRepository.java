package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;
import searchengine.model.SiteEntity;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    boolean existsByLemmaAndSiteId(String lemma, SiteEntity siteId);
    Lemma findByLemmaAndSiteId(String lemmaText, SiteEntity siteEntity);
}
