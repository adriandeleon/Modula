package com.modula.rds;

/**
 * What RDS has told us about the station currently tuned.
 *
 * @param programIdentification the station's numeric identity, or 0 if not yet received
 * @param programService the eight-character station name, e.g. "BBC R4"
 * @param radioText the free-text field, up to 64 characters — song titles, presenter names
 * @param programType 0–31; resolve to a name with {@link ProgramType}
 * @param trafficProgram whether the station carries traffic announcements
 * @param trafficAnnouncement whether one is on air right now
 */
public record StationInfo(
        int programIdentification,
        String programService,
        String radioText,
        int programType,
        boolean trafficProgram,
        boolean trafficAnnouncement,
        java.time.LocalDateTime clockTime,
        java.util.List<Long> alternativeFrequencies) {

    public static final StationInfo NONE = new StationInfo(0, "", "", 0, false, false, null, java.util.List.of());

    /** The shape most callers want; clock and alternatives are absent until a station sends them. */
    public StationInfo(
            int programIdentification,
            String programService,
            String radioText,
            int programType,
            boolean trafficProgram,
            boolean trafficAnnouncement) {
        this(
                programIdentification,
                programService,
                radioText,
                programType,
                trafficProgram,
                trafficAnnouncement,
                null,
                java.util.List.of());
    }

    public StationInfo {
        programService = programService == null ? "" : programService;
        radioText = radioText == null ? "" : radioText;
        alternativeFrequencies =
                alternativeFrequencies == null ? java.util.List.of() : java.util.List.copyOf(alternativeFrequencies);
    }

    /** Whether the station has sent a clock we were willing to believe. */
    public boolean hasClockTime() {
        return clockTime != null;
    }

    /** Whether anything has been decoded yet — i.e. whether this is worth showing. */
    public boolean isPresent() {
        return programIdentification != 0 || !programService.isBlank() || !radioText.isBlank();
    }
}
