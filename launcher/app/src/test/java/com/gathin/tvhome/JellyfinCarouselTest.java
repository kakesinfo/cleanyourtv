package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JellyfinCarouselTest {

    @Test
    public void normalizeUrlCleansTrailingSlashesAndWhitespace() {
        assertEquals("http://192.168.1.50:8096", JellyfinCarousel.normalizeUrl("http://192.168.1.50:8096/"));
        assertEquals("http://192.168.1.50:8096", JellyfinCarousel.normalizeUrl("  http://192.168.1.50:8096///  "));
        assertEquals("", JellyfinCarousel.normalizeUrl(null));
        assertEquals("", JellyfinCarousel.normalizeUrl("   "));
    }

    @Test
    public void interleaveAlternatesMoviesAndSeries() {
        List<JellyfinCarousel.BackdropRef> movies = Arrays.asList(
                new JellyfinCarousel.BackdropRef("m1", "tag1"),
                new JellyfinCarousel.BackdropRef("m2", "tag2")
        );
        List<JellyfinCarousel.BackdropRef> series = Arrays.asList(
                new JellyfinCarousel.BackdropRef("s1", "tag3"),
                new JellyfinCarousel.BackdropRef("s2", "tag4"),
                new JellyfinCarousel.BackdropRef("s3", "tag5")
        );

        List<JellyfinCarousel.BackdropRef> interleaved = JellyfinCarousel.interleave(movies, series);
        assertEquals(5, interleaved.size());
        assertEquals(new JellyfinCarousel.BackdropRef("m1", "tag1"), interleaved.get(0));
        assertEquals(new JellyfinCarousel.BackdropRef("s1", "tag3"), interleaved.get(1));
        assertEquals(new JellyfinCarousel.BackdropRef("m2", "tag2"), interleaved.get(2));
        assertEquals(new JellyfinCarousel.BackdropRef("s2", "tag4"), interleaved.get(3));
        assertEquals(new JellyfinCarousel.BackdropRef("s3", "tag5"), interleaved.get(4));
    }

    @Test
    public void parseSeasonItemsJsonDeduplicatesSeriesByLatestSeason() {
        String json = "{\n" +
                "  \"Items\": [\n" +
                "    {\n" +
                "      \"Id\": \"seasonA3\",\n" +
                "      \"IndexNumber\": 3,\n" +
                "      \"SeriesId\": \"seriesA\",\n" +
                "      \"ParentBackdropItemId\": \"seriesA\",\n" +
                "      \"ParentBackdropImageTags\": [\"tagA\"]\n" +
                "    },\n" +
                "    {\n" +
                "      \"Id\": \"seasonB1\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"seriesB\",\n" +
                "      \"ParentBackdropItemId\": \"seriesB\",\n" +
                "      \"ParentBackdropImageTags\": [\"tagB\"]\n" +
                "    },\n" +
                "    {\n" +
                "      \"Id\": \"seasonA2\",\n" +
                "      \"IndexNumber\": 2,\n" +
                "      \"SeriesId\": \"seriesA\",\n" +
                "      \"ParentBackdropItemId\": \"seriesA\",\n" +
                "      \"ParentBackdropImageTags\": [\"tagA\"]\n" +
                "    },\n" +
                "    {\n" +
                "      \"Id\": \"seasonA1\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"seriesA\",\n" +
                "      \"ParentBackdropItemId\": \"seriesA\",\n" +
                "      \"ParentBackdropImageTags\": [\"tagA\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<JellyfinCarousel.BackdropRef> refs = JellyfinCarousel.parseSeasonItemsJson(json, 10);
        assertEquals(2, refs.size());
        // Series A was encountered first via its latest season (Season 3), followed by Series B.
        assertEquals(new JellyfinCarousel.BackdropRef("seriesA", "tagA"), refs.get(0));
        assertEquals(new JellyfinCarousel.BackdropRef("seriesB", "tagB"), refs.get(1));
    }

    @Test
    public void parseSeasonItemsJsonSkipsSpecials() {
        String json = "{\n" +
                "  \"Items\": [\n" +
                "    {\n" +
                "      \"Id\": \"specialsA\",\n" +
                "      \"Name\": \"Specials\",\n" +
                "      \"IndexNumber\": 0,\n" +
                "      \"SeriesId\": \"seriesA\",\n" +
                "      \"ParentBackdropItemId\": \"seriesA\",\n" +
                "      \"ParentBackdropImageTags\": [\"tagSpecial\"]\n" +
                "    },\n" +
                "    {\n" +
                "      \"Id\": \"seasonB1\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"seriesB\",\n" +
                "      \"ParentBackdropItemId\": \"seriesB\",\n" +
                "      \"ParentBackdropImageTags\": [\"tagB\"]\n" +
                "    },\n" +
                "    {\n" +
                "      \"Id\": \"seasonA1\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"seriesA\",\n" +
                "      \"ParentBackdropItemId\": \"seriesA\",\n" +
                "      \"ParentBackdropImageTags\": [\"tagA\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<JellyfinCarousel.BackdropRef> refs = JellyfinCarousel.parseSeasonItemsJson(json, 10);
        assertEquals(2, refs.size());
        // Series B should come first because Series A specials are skipped and Series A season 1 is after.
        assertEquals(new JellyfinCarousel.BackdropRef("seriesB", "tagB"), refs.get(0));
        assertEquals(new JellyfinCarousel.BackdropRef("seriesA", "tagA"), refs.get(1));
    }

    @Test
    public void parseSeasonItemsJsonPrefersSeasonBackdropIfAvailable() {
        String json = "{\n" +
                "  \"Items\": [\n" +
                "    {\n" +
                "      \"Id\": \"season1\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"series1\",\n" +
                "      \"BackdropImageTags\": [\"seasonBackdropTag\"],\n" +
                "      \"ParentBackdropItemId\": \"series1\",\n" +
                "      \"ParentBackdropImageTags\": [\"parentBackdropTag\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<JellyfinCarousel.BackdropRef> refs = JellyfinCarousel.parseSeasonItemsJson(json, 10);
        assertEquals(1, refs.size());
        assertEquals(new JellyfinCarousel.BackdropRef("season1", "seasonBackdropTag"), refs.get(0));
    }

    @Test
    public void parseSeasonItemsJsonFallsBackToSeriesIdWhenParentBackdropItemIdMissing() {
        String json = "{\n" +
                "  \"Items\": [\n" +
                "    {\n" +
                "      \"Id\": \"season1\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"seriesFallback\",\n" +
                "      \"ParentBackdropImageTags\": [\"parentTag\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<JellyfinCarousel.BackdropRef> refs = JellyfinCarousel.parseSeasonItemsJson(json, 10);
        assertEquals(1, refs.size());
        assertEquals(new JellyfinCarousel.BackdropRef("seriesFallback", "parentTag"), refs.get(0));
    }

    @Test
    public void parseSeasonItemsJsonSkipsItemsWithoutBackdrop() {
        String json = "{\n" +
                "  \"Items\": [\n" +
                "    {\n" +
                "      \"Id\": \"seasonNoBackdrop\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"series1\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<JellyfinCarousel.BackdropRef> refs = JellyfinCarousel.parseSeasonItemsJson(json, 10);
        assertTrue(refs.isEmpty());
    }

    @Test
    public void parseSeasonItemsJsonRespectsLimit() {
        String json = "{\n" +
                "  \"Items\": [\n" +
                "    {\n" +
                "      \"Id\": \"s1\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"series1\",\n" +
                "      \"ParentBackdropImageTags\": [\"t1\"]\n" +
                "    },\n" +
                "    {\n" +
                "      \"Id\": \"s2\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"series2\",\n" +
                "      \"ParentBackdropImageTags\": [\"t2\"]\n" +
                "    },\n" +
                "    {\n" +
                "      \"Id\": \"s3\",\n" +
                "      \"IndexNumber\": 1,\n" +
                "      \"SeriesId\": \"series3\",\n" +
                "      \"ParentBackdropImageTags\": [\"t3\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<JellyfinCarousel.BackdropRef> refs = JellyfinCarousel.parseSeasonItemsJson(json, 2);
        assertEquals(2, refs.size());
    }
}
