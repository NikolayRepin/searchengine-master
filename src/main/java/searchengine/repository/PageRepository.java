package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.SiteEntity;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {
    Page findByContent(String content);
    boolean existsByPathAndSite(String path, SiteEntity site);
}
