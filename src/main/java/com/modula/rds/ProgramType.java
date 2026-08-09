package com.modula.rds;

import com.modula.band.Region;

/**
 * The programme-type code, 0–31.
 *
 * <p><b>The same number means different things on different continents.</b> Europe's RDS and North
 * America's RBDS assign the table independently — PTY 5 is "Education" in Europe and "Rock" in the
 * US — and nothing in the broadcast says which table is in use. So it is resolved from the user's
 * {@link Region}, the same setting that already picks channel spacing and de-emphasis.
 *
 * <p>Pure and stateless.
 */
public final class ProgramType {

    private static final String[] RDS = {
        "",
        "News",
        "Current Affairs",
        "Information",
        "Sport",
        "Education",
        "Drama",
        "Culture",
        "Science",
        "Varied",
        "Pop Music",
        "Rock Music",
        "Easy Listening",
        "Light Classical",
        "Serious Classical",
        "Other Music",
        "Weather",
        "Finance",
        "Children's Programmes",
        "Social Affairs",
        "Religion",
        "Phone In",
        "Travel",
        "Leisure",
        "Jazz Music",
        "Country Music",
        "National Music",
        "Oldies Music",
        "Folk Music",
        "Documentary",
        "Alarm Test",
        "Alarm"
    };

    private static final String[] RBDS = {
        "",
        "News",
        "Information",
        "Sports",
        "Talk",
        "Rock",
        "Classic Rock",
        "Adult Hits",
        "Soft Rock",
        "Top 40",
        "Country",
        "Oldies",
        "Soft",
        "Nostalgia",
        "Jazz",
        "Classical",
        "Rhythm and Blues",
        "Soft Rhythm and Blues",
        "Foreign Language",
        "Religious Music",
        "Religious Talk",
        "Personality",
        "Public",
        "College",
        "",
        "",
        "",
        "",
        "",
        "Weather",
        "Emergency Test",
        "Emergency"
    };

    private ProgramType() {}

    /** The name for a code, or empty for "none" and for the codes each table leaves unassigned. */
    public static String name(int code, Region region) {
        if (code < 0 || code > 31) {
            return "";
        }
        return region == Region.AMERICAS ? RBDS[code] : RDS[code];
    }
}
