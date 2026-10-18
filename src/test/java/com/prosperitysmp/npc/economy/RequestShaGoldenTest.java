package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4: request_sha256 for one fixed event of each source. The expected values were computed outside Java from the
 * dev.63 formula ("transfer-v1|id|SOURCE|property|counterparty|epoch|benefitUnit|day|cents|key", SHA-256, lowercase
 * hex), so a change to the encoding, the field order or the enum names fails here. dev.65 reuses these.
 */
class RequestShaGoldenTest {
    private static final String[] EXPECTED = {
            "24b1cc18a8ac1d8c1ada2e1707c80a855308f759ec136eb240bdbbbdb24bb24e",
            "1079f9137faafb3bc7a6504bb0b4acbd6a91d0a8a304035104a57bc13a609345",
            "6df6d44277b0c5dff746f3b64257eaf0291fc5cc7768df5016575b0ee20f30a2",
            "fcc2e4cc7dced0e5025f0480a665347ae85d4086d3761cfa2f04b46e0d8337d6",
            "f11b66290b0f9671660cdd49b97842d167c776b39708a683726e14a3f27ea0b0",
            "4f08fb8dec1e8465e48075a354b1055c946649b621056e606131554e1df7b81f",
            "e52b5af8f2c608be388620132af945365ec0ea13ab7eba75ab16a64cabdf1407",
            // dev.65 BILLS, appended; the seven above are the dev.63 values, unchanged.
            "11f8b59bacf5174544457d1d69d67719e162a1e090c0bee2893c6662fc836205"};

    @Test void oneFixedEventPerSourceKeepsItsHash() {
        EventSource[] sources = EventSource.values();
        assertEquals(EXPECTED.length, sources.length, "one golden per source");
        for (int i = 0; i < sources.length; i++) {
            UUID property = new UUID(0, 100 + i), counterparty = new UUID(0, 200 + i);
            UUID benefitUnit = i % 2 == 1 ? null : new UUID(0, 300 + i);
            String day = "2026-10-1" + (i % 10);
            String key = sources[i].key() + ":" + day + ":" + property;
            assertEquals(EXPECTED[i], TransferJournal.requestSha256(1000 + i, sources[i], property, counterparty, i + 1,
                    benefitUnit, day, 1234 + i * 1111L, key), sources[i].name());
        }
    }

    @Test void theHashSeesEveryField() {
        // Negative control: one changed cent gives a different hash.
        UUID property = new UUID(0, 100), counterparty = new UUID(0, 200), unit = new UUID(0, 300);
        String a = TransferJournal.requestSha256(1000, EventSource.RENT, property, counterparty, 1, unit, "2026-10-10", 1234, "k");
        String b = TransferJournal.requestSha256(1000, EventSource.RENT, property, counterparty, 1, unit, "2026-10-10", 1235, "k");
        assertNotEquals(a, b);
    }
}
