package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;
import searchengine.model.SiteEntity;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    boolean existsByLemmaAndSiteId(String lemma, SiteEntity siteId);
    Lemma findByLemmaAndSite(String lemmaText, SiteEntity siteEntity);
    @Query("SELECT COUNT(l) FROM Lemma l WHERE l.site = :site")
    Integer countLemmaBySite(@Param("site") SiteEntity site);

    Lemma findByLemma(String lemmaText);
}
