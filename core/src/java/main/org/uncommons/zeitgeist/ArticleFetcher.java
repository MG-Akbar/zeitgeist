// ============================================================================
//   Copyright 2009-2012 Daniel W. Dyer
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
// ============================================================================
package org.uncommons.zeitgeist;

import com.sun.syndication.fetcher.FeedFetcher;
import com.sun.syndication.fetcher.impl.FeedFetcherCache;
import com.sun.syndication.fetcher.impl.HashMapFeedInfoCache;
import com.sun.syndication.fetcher.impl.HttpURLFeedFetcher;
import com.sun.syndication.fetcher.impl.SyndFeedInfo;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.grlea.log.SimpleLogger;
import org.uncommons.zeitgeist.filters.ArticleFilter;

/**
 * Class for downloading articles from RSS/Atom feeds.
 * @author Daniel Dyer
 */
public class ArticleFetcher
{
    private static final SimpleLogger LOG = new SimpleLogger(ArticleFetcher.class);

    private final FeedFetcher fetcher;

    public ArticleFetcher()
    {
        this(new TimeoutFeedFetcher(HashMapFeedInfoCache.getInstance()));
    }

    
    ArticleFetcher(FeedFetcher fetcher)
    {
        this.fetcher = fetcher;
    }


    /**
     * Download the specified feeds and extract the articles.  If any of the feeds cannot be retrieved
     * or parsed, an error will be logged but there will be no exception and the other feeds will be processed
     * as normal.
     * @param feeds A list of URLs of RSS/Atom feeds to download.
     * @param filters Only return articles that match all of these filters.
     * @return A list of articles extracted from the specified feed.  The articles will be grouped
     * by feed, in the order that the feeds were specified.
     */
    public List<Article> getArticles(List<URL> feeds, List<? extends ArticleFilter> filters)
    {
        List<Article> articles = new LinkedList<Article>();
        try
        {
            // Download the feeds in parallel so that it completes quicker.
            ExecutorService executor = Executors.newFixedThreadPool(feeds.size());
            List<Callable<List<Article>>> tasks = new ArrayList<Callable<List<Article>>>(feeds.size());
            for (final URL feedURL : feeds)
            {
                tasks.add(new FeedDownloadTask(fetcher, feedURL, filters, true));
            }

            List<Future<List<Article>>> results = executor.invokeAll(tasks);
            for (Future<List<Article>> result : results)
            {
                try
                {
                    articles.addAll(result.get());
                }
                catch (ExecutionException ex)
                {
                    // Log the failure for this feed, but carry on with other feeds.
                    LOG.errorException(ex.getCause());
                }
            }
            executor.shutdown();
            Collections.shuffle(articles);
            LOG.info("Downloaded " + articles.size() + " articles.");
        }
        catch (InterruptedException ex)
        {
            // Restore interrupt status.
            Thread.currentThread().interrupt();
            ex.printStackTrace();
        }
        return articles;
    }


    /**
     * A customised version of the ROME fetcher that doesn't hang forever waiting for a response.
     */
    private static class TimeoutFeedFetcher extends HttpURLFeedFetcher
    {
        private static final int TIMEOUT = 60000;

        TimeoutFeedFetcher(FeedFetcherCache cache)
        {
            super(cache);
        }


        /**
         * Conceptually this is not the right place to modify the timeout settings but it's the only
         * place that exposes access to the underlying URLConnection.
         */
        @Override
        protected void setRequestHeaders(URLConnection connection, SyndFeedInfo syndFeedInfo)
        {
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            super.setRequestHeaders(connection, syndFeedInfo);
        }
    }
}
