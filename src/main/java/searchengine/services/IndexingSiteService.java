package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.response.ResponseBoolean;
import searchengine.dto.statistics.response.ResponseError;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.SiteEntity;
import searchengine.model.StatusIndexingSite;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.util.PageCrawlerTask;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingSiteService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final SitesList sitesList;
    private ForkJoinPool forkJoinPool;
    private final AtomicBoolean isIndexingRunning = new AtomicBoolean(false);


    public void deleteSiteData(String url) {
        SiteEntity site = siteRepository.findByUrl(url);
        if (site != null) {
            log.info("Удалить данные сайта " + url);
            siteRepository.delete(site);
        } else {
            log.warn("Сайт {} не найден, начинаем индексацию", url);
        }
    }


    public void deleteAllSiteDate() {
        List<Site> sites = sitesList.getSites();
        for (Site site : sites) {
            deleteSiteData(site.getUrl());
        }
    }


    public SiteEntity createEntryInTableSite(Site site) {
        SiteEntity siteEntity = new SiteEntity();
        siteEntity.setUrl(site.getUrl());
        siteEntity.setName(site.getName());
        siteEntity.setStatusTime(Instant.now());
        siteEntity.setStatus(StatusIndexingSite.INDEXING);
        siteEntity.setLastError("");
        return siteRepository.save(siteEntity);

    }


    public ResponseBoolean startIndexing() {
        if (isIndexingRunning.get()) {
            log.info("Индексация уже запущена");
            return new ResponseError(false, "Индексация уже запущена");
        }
        PageCrawlerTask.clearVisitedSite();
        log.info("Очискта списка посещенных сайтов");
        isIndexingRunning.set(true);
        log.info("status true");
        forkJoinPool = new ForkJoinPool();
        if (forkJoinPool.getActiveThreadCount() != 0) {
            isIndexingRunning.set(false);
        }
        deleteAllSiteDate();
        sitesList.getSites().forEach(site -> {
            SiteEntity siteEntity = createEntryInTableSite(site);
            PageCrawlerTask pageCrawlerTask = null;
            try {
                pageCrawlerTask = new PageCrawlerTask(site.getUrl(), site.getUrl(), pageRepository, siteRepository, lemmaRepository, sitesList, siteEntity);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            forkJoinPool.execute(pageCrawlerTask);
        });

        return new ResponseBoolean(true);
    }


    public ResponseBoolean stopIndexing() {
        if (!isIndexingRunning.get()) {
            log.info("Индексация не запущена");
            return new ResponseError(false, "Индексация не запущена");
        }
        log.info("Остновка индексации");
        isIndexingRunning.set(false);
        log.info("status false");
        forkJoinPool.shutdown();
        return new ResponseBoolean(true);
    }


    //TODO 1-  КОД ПОЛУЧЕН
    // 2 - ПРЕОБРАЩОВТАЬ В НАБОР ЛЕММ И ИХ КОЛ-ВО
    // 3 - СОХРАНИТЬ В ТБЛИЦЫ БД ЛЕММЫ И ИНДЕКС
    //    Леммы должны добавляться в таблицу lemma. Если леммы в таблице ещё нет, она должна туда добавляться со значением frequency, равным 1. Если же лемма в таблице уже есть, число frequency необходимо увеличить на 1. Число frequency у каждой леммы в итоге должно соответствовать количеству страниц, на которых эта лемма встречается хотя бы один раз.
    // Связки лемм и страниц должны добавляться в таблицу index. Для каждой пары «лемма-страница» в этой таблице должна создаваться одна запись с указанием количества данной леммы на страницы в поле rank.
}
