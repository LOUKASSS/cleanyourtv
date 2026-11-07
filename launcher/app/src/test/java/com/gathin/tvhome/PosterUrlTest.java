package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PosterUrlTest {
    @Test
    public void twitchSizeBecomes1280x720() {
        assertEquals("https://static-cdn.jtvnw.net/cf_vods/d3abc/0123_user_42//thumb/thumb0-1280x720.jpg",
                PosterUrl.upgrade("https://static-cdn.jtvnw.net/cf_vods/d3abc/0123_user_42//thumb/thumb0-640x360.jpg"));
        assertEquals("https://static-cdn.jtvnw.net/previews-ttv/live_user_someone-1280x720.jpg",
                PosterUrl.upgrade("https://static-cdn.jtvnw.net/previews-ttv/live_user_someone-320x180.jpg"));
    }

    @Test
    public void twitchTemplateIsFilled() {
        assertEquals("https://static-cdn.jtvnw.net/previews-ttv/live_user_x-1280x720.jpg",
                PosterUrl.upgrade("https://static-cdn.jtvnw.net/previews-ttv/live_user_x-{width}x{height}.jpg"));
        assertEquals("https://static-cdn.jtvnw.net/previews-ttv/live_user_x-1280x720.jpg",
                PosterUrl.upgrade("https://static-cdn.jtvnw.net/previews-ttv/live_user_x-%7Bwidth%7Dx%7Bheight%7D.jpg"));
    }

    @Test
    public void twitchBoxArtAndLargePicturesStay() {
        String boxArt = "https://static-cdn.jtvnw.net/ttv-boxart/12345-285x380.jpg";
        assertEquals(boxArt, PosterUrl.upgrade(boxArt));
        String large = "https://static-cdn.jtvnw.net/cf_vods/a/b/thumb/thumb0-1920x1080.jpg";
        assertEquals(large, PosterUrl.upgrade(large));
    }

    @Test
    public void youtubeStillBecomesMaxres() {
        assertEquals("https://i.ytimg.com/vi/AbCdEfGhIjK/maxresdefault.jpg",
                PosterUrl.upgrade("https://i.ytimg.com/vi/AbCdEfGhIjK/sddefault.jpg"));
        assertEquals("https://i.ytimg.com/vi_webp/AbCdEfGhIjK/maxresdefault.webp?x=1",
                PosterUrl.upgrade("https://i.ytimg.com/vi_webp/AbCdEfGhIjK/hqdefault.webp?x=1"));
        String other = "https://i.ytimg.com/vi/AbCdEfGhIjK/hq720.jpg";
        assertEquals(other, PosterUrl.upgrade(other));
    }

    @Test
    public void disneyScaleBecomes1280x720() {
        assertEquals("https://disney.images.edge.bamgrid.com/ripcut-delivery/v2/variant/disney/0a-b/scale?format=webp&height=720&width=1280",
                PosterUrl.upgrade("https://disney.images.edge.bamgrid.com/ripcut-delivery/v2/variant/disney/0a-b/scale?format=webp&height=306&width=544"));
        // Not 16:9, or no height: left alone.
        String tall = "https://disney.images.edge.bamgrid.com/ripcut-delivery/v2/variant/disney/0a-b/scale?height=816&width=544";
        assertEquals(tall, PosterUrl.upgrade(tall));
        String noHeight = "https://disney.images.edge.bamgrid.com/ripcut-delivery/v2/variant/disney/0a-b/scale?width=544";
        assertEquals(noHeight, PosterUrl.upgrade(noHeight));
    }

    @Test
    public void maxWidthBecomes1920() {
        assertEquals("https://images.cdn.prd.api.discomax.com/xfKaW/abc.jpeg?w=1920&q=85&p=true&f=jpg",
                PosterUrl.upgrade("https://images.cdn.prd.api.discomax.com/xfKaW/abc.jpeg?w=600&q=85&p=true&f=jpg"));
        String wide = "https://images.cdn.prd.api.discomax.com/xfKaW/abc.jpeg?w=1000&q=85";
        assertEquals(wide, PosterUrl.upgrade(wide));
    }

    @Test
    public void otherServicesAndSchemesStay() {
        String[] same = {
                "https://occ-0-1-2.1.nflxso.net/dnm/api/v6/abc/AAAAB.jpg?client=det&platform=sbn",
                "https://is1-ssl.mzstatic.com/image/thumb/abc/1920x1080.jpg",
                "content://com.example.provider/45",
                "http://static-cdn.jtvnw.net/x-320x180.jpg",
                "not a url",
        };
        for (String s : same) assertEquals(s, PosterUrl.upgrade(s));
        assertNull(PosterUrl.upgrade(null));
    }

    @Test
    public void patternDropsQueryValuesAndShortensIds() {
        String p = PosterUrl.pattern("https://occ-0-1-2.1.nflxso.net/dnm/api/v6/AAAABetA8_9VaC3q7W8sI1g781z1DUK.jpg?client=det&token=secret");
        assertTrue(p, p.startsWith("occ-0-1-2.1.nflxso.net/dnm/api/v6/AAAABe…"));
        assertTrue(p, p.endsWith(" ? client token"));
        assertFalse(p, p.contains("secret"));
        assertEquals("i.ytimg.com/vi/AbCdEfGhIjK/sddefault.jpg", PosterUrl.pattern("https://i.ytimg.com/vi/AbCdEfGhIjK/sddefault.jpg"));
    }

    @Test
    public void hostIgnoresUserInfoAndPort() {
        assertEquals("cdn.example.com", PosterUrl.host("https://u:p@CDN.example.com:443/a"));
        assertNull(PosterUrl.host("no scheme"));
    }

    @Test
    public void paramReadsNumbersOnly() {
        assertEquals(544, PosterUrl.param("https://x/y?height=306&width=544", "width"));
        assertEquals(-1, PosterUrl.param("https://x/y?width=abc", "width"));
        assertEquals(-1, PosterUrl.param("https://x/y?w=1", "width"));
    }
}
