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
        boolean trafficAnnouncement) {

    public static final StationInfo NONE = new StationInfo(0, "", "", 0, false, false);

    public StationInfo {
        programService = programService == null ? "" : programService;
        radioText = radioText == null ? "" : radioText;
    }

    /** Whether anything has been decoded yet — i.e. whether this is worth showing. */
    public boolean isPresent() {
        return programIdentification != 0 || !programService.isBlank() || !radioText.isBlank();
    }
}
