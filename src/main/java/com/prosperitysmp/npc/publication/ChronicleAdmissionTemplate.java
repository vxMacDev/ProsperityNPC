package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;

/** Internal preview only. A template is neither source verification nor publication authorization. */
public final class ChronicleAdmissionTemplate {
    private ChronicleAdmissionTemplate() { }

    /** No source UUID, owner, resident name, financial value or relationship state. */
    public record Preview(String eventKey, String templateVersion, String world, String region,
                          LocalDate moveDate, String title, String text) { }

    /**
     * Caller must supply a repository-verified receipt and a separately verified historical property mapping.
     * Publication must recheck its own policy/scope; this method performs no I/O or approval lookup.
     * Date is the calendar date in the supplied server zone, not the four-AM economy day.
     * The stable event key supports correlation/deduplication; hashing is not anonymization.
     */
    public static Preview render(Receipt receipt, Approval mapping, ZoneId serverZone) {
        Objects.requireNonNull(receipt); Objects.requireNonNull(mapping); Objects.requireNonNull(serverZone);
        var request=Objects.requireNonNull(receipt.request());
        if (!request.propertyId().equals(mapping.propertyId()) || !request.worldId().equals(mapping.worldId()))
            throw new IllegalArgumentException("Admission property mapping mismatch");
        LocalDate date=receipt.appliedAt().atZone(serverZone).toLocalDate();
        if (date.getYear()<1 || date.getYear()>9999) throw new IllegalArgumentException("Invalid admission date");
        return new Preview(eventKey(request.requestId().toString()), "npc-admission-story-v1",
                mapping.publicWorldKey(), mapping.regionId(), date, "A new arrival at " + mapping.regionId(),
                "A resident moved into " + mapping.regionId() + " on " + date + ".");
    }

    private static String eventKey(String requestId) {
        // Stable across wording, mapping and policy revisions: editing copy must not create a new event.
        byte[] bytes=("npc-housing-admission\n"+requestId).getBytes(StandardCharsets.UTF_8);
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
