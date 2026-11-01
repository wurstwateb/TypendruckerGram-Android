// Checks app.typendruckergram.sync against docs/sync/test-vectors.json on the
// plain JVM. Run by .github/workflows/sync.yml:
//   javac -cp bcprov.jar:gson.jar -d out SyncCrypto.java SyncEvents.java tests/sync/SyncVectorsTest.java
//   java -cp out:bcprov.jar:gson.jar SyncVectorsTest docs/sync/test-vectors.json

import app.typendruckergram.sync.SyncCrypto;
import app.typendruckergram.sync.SyncEvents;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class SyncVectorsTest {

    private static int failures = 0;
    private static int checked = 0;

    private static void check(boolean ok, String what) {
        checked++;
        if (!ok) {
            failures++;
            System.err.println("FAIL: " + what);
        }
    }

    private static byte[] hex(JsonObject o, String key) {
        return SyncCrypto.fromHex(o.get(key).getAsString());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: SyncVectorsTest test-vectors.json");
            System.exit(2);
        }
        JsonObject v = JsonParser.parseString(
                new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8)).getAsJsonObject();

        roundTrips();

        byte[] ownerPrivate = hex(v, "ownerPrivateHex");
        byte[] ownerPublic = hex(v, "ownerPublicHex");
        check(Arrays.equals(SyncCrypto.publicFromPrivate(ownerPrivate), ownerPublic), "public from private");

        for (JsonElement element : v.getAsJsonArray("seal")) {
            JsonObject item = element.getAsJsonObject();
            byte[] plain = hex(item, "plaintextHex");
            byte[] blob = hex(item, "blobHex");
            String tag = item.get("blobHex").getAsString().substring(0, 16);

            byte[] sealed = SyncCrypto.sealWith(ownerPublic, plain, hex(item, "ephemeralPrivateHex"), hex(item, "nonceHex"));
            check(Arrays.equals(sealed, blob), "seal matches vector " + tag);
            check(Arrays.equals(SyncCrypto.open(ownerPrivate, blob), plain), "open vector " + tag);

            // Decoding and re-encoding the event gives the same canonical bytes.
            SyncEvents.Event event = SyncEvents.decode(plain);
            check(event != null, "decode event " + tag);
            if (event == null) {
                continue;
            }
            byte[] encoded = SyncEvents.encode(event);
            check(Arrays.equals(encoded, plain), "canonical encoding " + tag
                    + "\n  got:  " + new String(encoded, StandardCharsets.UTF_8)
                    + "\n  want: " + new String(plain, StandardCharsets.UTF_8));

            List<String> keys = SyncEvents.dedupKeys(event);
            List<String> expected = new java.util.ArrayList<>();
            for (JsonElement k : item.getAsJsonArray("dedupKeys")) {
                expected.add(k.getAsString());
            }
            check(keys.equals(expected), "dedup keys " + tag);
            if (item.has("relayKey")) {
                check(SyncEvents.relayKey(event).equals(item.get("relayKey").getAsString()), "relay key " + tag);
            }
        }

        JsonObject p = v.getAsJsonObject("pair");
        String normalized = p.get("normalized").getAsString();
        check(normalized.equals(SyncCrypto.normalizePairCode(p.get("code").getAsString())), "normalize code");
        check(normalized.equals(SyncCrypto.normalizePairCode(p.get("codeAsTyped").getAsString())), "normalize typed code");
        check(SyncCrypto.pairLookupHash(normalized).equals(p.get("lookupHash").getAsString()), "pair lookup hash");
        check(Arrays.equals(SyncCrypto.pairEncryptWith(normalized, hex(p, "payloadHex"), hex(p, "nonceHex")),
                hex(p, "encryptedHex")), "pair encrypt");
        check(Arrays.equals(SyncCrypto.pairDecrypt(normalized, hex(p, "encryptedHex")), hex(p, "payloadHex")),
                "pair decrypt");

        System.out.println(checked + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void roundTrips() {
        SyncCrypto.KeyPair pair = SyncCrypto.generateKeyPair();
        byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] blob = SyncCrypto.seal(pair.publicKey, text);
        check(Arrays.equals(SyncCrypto.open(pair.privateKey, blob), text), "seal round trip");
        check(!Arrays.equals(SyncCrypto.seal(pair.publicKey, text), blob), "seal is randomized");
        check(SyncCrypto.open(SyncCrypto.generateKeyPair().privateKey, blob) == null, "wrong key rejected");
        blob[50] ^= 1;
        check(SyncCrypto.open(pair.privateKey, blob) == null, "tampered blob rejected");
        check(SyncCrypto.open(pair.privateKey, new byte[40]) == null, "short blob rejected");

        String code = SyncCrypto.newPairCode();
        check(code.length() == 14 && code.charAt(4) == '-' && code.charAt(9) == '-', "pair code format");
        String normalized = SyncCrypto.normalizePairCode(code);
        check(normalized != null, "own code normalizes");
        check(SyncCrypto.normalizePairCode("ABCD-EFGH") == null, "short code rejected");
        check(SyncCrypto.normalizePairCode("ABCD-EFGH-JKM!") == null, "bad char rejected");
        byte[] data = SyncCrypto.pairEncrypt(normalized, text);
        check(Arrays.equals(SyncCrypto.pairDecrypt(normalized, data), text), "pair round trip");
        check(SyncCrypto.pairDecrypt("000000000000", data) == null, "wrong pair code rejected");

        SyncEvents.Event edit = new SyncEvents.Event();
        edit.type = SyncEvents.TYPE_EDIT;
        edit.account = 1;
        edit.previous = new SyncEvents.Snapshot();
        edit.previous.text = "line\n\"quoted\" \\ tab\t\u0001";
        SyncEvents.Event back = SyncEvents.decode(SyncEvents.encode(edit));
        check(back != null && edit.previous.text.equals(back.previous.text), "string escaping round trip");
    }
}
