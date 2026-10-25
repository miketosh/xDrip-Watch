package jamorham.keks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * xDrip-Watch: regression cover for libkeks on the BouncyCastle version the app
 * ships. The golden values were recorded with bcprov-jdk15to18 1.68 (the
 * version before PumpX2 required 1.77); they must not change when BouncyCastle
 * is upgraded, or G7 pairing and saved-key logins would break.
 */
public class KeksBouncyCastleTest {

    private static final byte[] PARTY_1 = "test-party-one".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PARTY_2 = "test-party-two".getBytes(StandardCharsets.UTF_8);
    private static final BigInteger D1 = new BigInteger(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    private static final BigInteger D2 = new BigInteger(
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);

    // Recorded with bcprov-jdk15to18 1.68.
    private static final String GOLDEN_D1_POINT = "04b612ae0153ab4f90b3255f8518f3480063359506e9a450adaf32270c3e16fb5049647ae2f5387556359891eb7930d478de12bacf7fb6548fcb0b021bb117d062";
    private static final String GOLDEN_ZK_HASH = "6236e1f83e63ba256f5d23539da590fdff0c0967b36451eb62fcf21f98a41847";
    private static final String GOLDEN_PACKET = "b612ae0153ab4f90b3255f8518f3480063359506e9a450adaf32270c3e16fb5049647ae2f5387556359891eb7930d478de12bacf7fb6548fcb0b021bb117d062c6a23e1b389f3f4433b2c88d4b225cb585d5c6e20efcf37c35bfca907e14b3baaae6220a8c27fd53b0931b5d6ea1c8d9b68e11fce71b5d00233622fa6760561a4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5";

    @Test
    public void scalarMultiplicationMatchesRecordedValue() {
        final ECPoint p = Curve.G.multiply(D1).normalize();
        assertEquals(GOLDEN_D1_POINT, Hex.toHexString(p.getEncoded(false)));
    }

    @Test
    public void zeroKnowledgeHashMatchesRecordedValue() {
        final ECPoint p1 = Curve.G.multiply(D1).normalize();
        final ECPoint p2 = Curve.G.multiply(D2).normalize();
        final BigInteger hash = Calc.getZeroKnowledgeHash(Curve.G, p1, p2, PARTY_1);
        assertEquals(GOLDEN_ZK_HASH, hash.toString(16));
    }

    @Test
    public void packetSerializationMatchesRecordedValueAndRoundTrips() {
        final ECPoint p1 = Curve.G.multiply(D1).normalize();
        final ECPoint p2 = Curve.G.multiply(D2).normalize();
        final Packet packet = new Packet(D2, p1, p2);
        final byte[] bytes = packet.output();
        assertEquals(GOLDEN_PACKET, Hex.toHexString(bytes));
        final Packet parsed = Packet.parse(bytes);
        assertNotNull(parsed);
        assertEquals(D2, parsed.getHash());
        assertEquals(p1, parsed.getPublicKeyPoint1());
        assertEquals(p2, parsed.getPublicKeyPoint2());
    }

    @Test
    public void jpakeBetweenTwoPartiesAgreesOnSharedKey() {
        final Context a = newContext(PARTY_1, PARTY_2, "1234");
        final Context b = newContext(PARTY_2, PARTY_1, "1234");
        exchange(a, b);

        assertTrue(Calc.validateRound1Packet(a));
        assertTrue(Calc.validateRound2Packet(a));
        assertTrue(Calc.validateRound3Packet(a));
        assertTrue(Calc.validateRound1Packet(b));
        assertTrue(Calc.validateRound2Packet(b));
        assertTrue(Calc.validateRound3Packet(b));
        assertArrayEquals(Calc.getSharedKey(a), Calc.getSharedKey(b));
        assertEquals(16, Calc.getShortSharedKey(a).length);
    }

    @Test
    public void jpakeWithWrongPasswordDisagrees() {
        final Context a = newContext(PARTY_1, PARTY_2, "1234");
        final Context b = newContext(PARTY_2, PARTY_1, "4321");
        exchange(a, b);
        assertFalse(Arrays.equals(Calc.getSharedKey(a), Calc.getSharedKey(b)));
    }

    @Test
    public void tamperedRound1ProofIsRejected() {
        final Context a = newContext(PARTY_1, PARTY_2, "1234");
        final Context b = newContext(PARTY_2, PARTY_1, "1234");
        final Packet p = Calc.getRound1Packet(b);
        a.packet[1] = new Packet(p.getHash().add(BigInteger.ONE), p.getPublicKeyPoint1(), p.getPublicKeyPoint2());
        assertFalse(Calc.validateRound1Packet(a));
    }

    @Test
    public void encodedKeyPairRoundTripsAndChallengeSignatureVerifies() throws Exception {
        final KeyPairGeneratorSpi.EC generator = new KeyPairGeneratorSpi.EC();
        generator.initialize(Curve.curveSpec);
        final java.security.KeyPair jca = generator.generateKeyPair();
        final byte[][] encoded = {jca.getPublic().getEncoded(), jca.getPrivate().getEncoded()};

        final KeyPair keys = KeyPair.fromBytes(encoded);
        assertNotNull(keys);
        assertEquals(Curve.G.multiply(keys.getPrivateKey()).normalize(), keys.getPublicKey().normalize());

        final byte[] challenge = new byte[18];
        for (int i = 0; i < challenge.length; i++) challenge[i] = (byte) (i * 7 + 3);
        final byte[] response = Calc.challenger(encoded[1], challenge);
        assertNotNull(response);
        assertEquals(64, response.length);

        final byte[] signed = Arrays.copyOfRange(challenge, 2, 18);
        final SHA256Digest digest = new SHA256Digest();
        digest.update(signed, 0, signed.length);
        final byte[] hash = new byte[32];
        digest.doFinal(hash, 0);
        final ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, new ECPublicKeyParameters(keys.getPublicKey(), new ECDomainParameters(
                Curve.curve, Curve.G, Curve.Q, Curve.curveSpec.getH())));
        assertTrue(verifier.verifySignature(hash,
                new BigInteger(1, Arrays.copyOfRange(response, 0, 32)),
                new BigInteger(1, Arrays.copyOfRange(response, 32, 64))));
    }

    private static Context newContext(byte[] self, byte[] peer, String password) {
        final Context c = new Context();
        c.alice = self;
        c.bob = peer;
        c.password = password;
        c.keyA = new KeyPair();
        c.KeyB = new KeyPair();
        return c;
    }

    /** Runs the three J-PAKE rounds, passing each packet through its wire format. */
    private static void exchange(Context a, Context b) {
        a.packet[1] = Packet.parse(Calc.getRound1Packet(b).output());
        b.packet[1] = Packet.parse(Calc.getRound1Packet(a).output());
        a.packet[2] = Packet.parse(Calc.getRound2Packet(b).output());
        b.packet[2] = Packet.parse(Calc.getRound2Packet(a).output());
        final Packet a3 = Calc.getRound3Packet(a);
        final Packet b3 = Calc.getRound3Packet(b);
        a.packet[3] = Packet.parse(b3.output());
        b.packet[3] = Packet.parse(a3.output());
    }
}
