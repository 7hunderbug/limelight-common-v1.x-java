package com.limelight.nvstream.http;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;

import org.xmlpull.v1.XmlPullParserException;

import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.Socket;
import java.security.*;
import java.security.cert.*;
import java.util.Arrays;

public class PairingManager {

	private NvHTTP http;
	
	private PrivateKey pk;
	private X509Certificate cert;
	private SecretKey aesKey;
	
	byte[] privKeyBytes;
	byte[] pubKeyBytes;
	
	public enum PairState {
		NOT_PAIRED,
		PAIRED,
		PIN_WRONG,
		FAILED
	}
	
	public PairingManager(NvHTTP http, LimelightCryptoProvider cryptoProvider) {
		this.http = http;
		this.cert = cryptoProvider.getClientCertificate();
		this.pk = cryptoProvider.getClientPrivateKey();
		
		// Update the trust manager and key manager to use our certificate and PK
	    installSslKeysAndTrust();
	}
	
	public void installSslKeysAndTrust() {
		// Create a trust manager that does not validate certificate chains
		TrustManager[] trustAllCerts = new TrustManager[] { 
				new X509TrustManager() {
					public X509Certificate[] getAcceptedIssuers() { 
						return new X509Certificate[0]; 
					}
					public void checkClientTrusted(X509Certificate[] certs, String authType) {}
					public void checkServerTrusted(X509Certificate[] certs, String authType) {}
				}};

		KeyManager[] ourKeyman = new KeyManager[] {
				new X509KeyManager() {
					public String chooseClientAlias(String[] keyTypes,
							Principal[] issuers, Socket socket) {
						return "Limelight-RSA";
					}

					public String chooseServerAlias(String keyType, Principal[] issuers,
							Socket socket) {
						return null;
					}

					public X509Certificate[] getCertificateChain(String alias) {
						return new X509Certificate[] {cert};
					}

					public String[] getClientAliases(String keyType, Principal[] issuers) {
						return null;
					}

					public PrivateKey getPrivateKey(String alias) {
						return pk;
					}

					public String[] getServerAliases(String keyType, Principal[] issuers) {
						return null;
					}
				}
		};

		// Ignore differences between given hostname and certificate hostname
		HostnameVerifier hv = new HostnameVerifier() {
			public boolean verify(String hostname, SSLSession session) { return true; }
		};

		// Install the all-trusting trust manager
		try {
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(ourKeyman, trustAllCerts, new SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			HttpsURLConnection.setDefaultHostnameVerifier(hv);
		} catch (Exception e) {}
	}
	
	final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
	private static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
	private static byte[] hexToBytes(String s) {
	    int len = s.length();
	    byte[] data = new byte[len / 2];
	    for (int i = 0; i < len; i += 2) {
	        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
	                             + Character.digit(s.charAt(i+1), 16));
	    }
	    return data;
	}
	
	private X509Certificate extractPlainCert(String text) throws XmlPullParserException, IOException, CertificateException
	{
		String certText = NvHTTP.getXmlString(text, "plaincert");
		byte[] certBytes = hexToBytes(certText);
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
	    return (X509Certificate)cf.generateCertificate(new ByteArrayInputStream(certBytes));
	}
	
	private byte[] generateRandomBytes(int length)
	{
		byte[] rand = new byte[length];
		new SecureRandom().nextBytes(rand);
		return rand;
	}
	
	private static byte[] saltPin(byte[] salt, String pin) throws UnsupportedEncodingException {
		byte[] saltedPin = new byte[salt.length + pin.length()];
		System.arraycopy(salt, 0, saltedPin, 0, salt.length);
		System.arraycopy(pin.getBytes("UTF-8"), 0, saltedPin, salt.length, pin.length());
		return saltedPin;
	}
	
	private static byte[] toSHA1Bytes(byte[] convertme) {
	    MessageDigest md = null;
	    try {
	        md = MessageDigest.getInstance("SHA-1");
	    }
	    catch(NoSuchAlgorithmException e) {
	        e.printStackTrace();
	    } 
	    return md.digest(convertme);
	}
	
	private static boolean verifySignature(byte[] data, byte[] signature, Certificate cert) throws NoSuchAlgorithmException, SignatureException, InvalidKeyException {
		Signature sig = Signature.getInstance("SHA256withRSA");
		sig.initVerify(cert.getPublicKey());
		sig.update(data);
		return sig.verify(signature);
	}
	
	private static byte[] signData(byte[] data, PrivateKey key) throws NoSuchAlgorithmException, SignatureException, InvalidKeyException {
		Signature sig = Signature.getInstance("SHA256withRSA");
		sig.initSign(key);
		sig.update(data);
		byte[] signature = new byte[256];
		sig.sign(signature, 0, signature.length);
		return signature;
	}
	
	private static byte[] decryptAes(byte[] encryptedData, SecretKey secretKey) throws NoSuchAlgorithmException, SignatureException,
	InvalidKeyException, ShortBufferException, IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException {
		Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
		
		int blockRoundedSize = ((encryptedData.length + 15) / 16) * 16;
		byte[] blockRoundedEncrypted = Arrays.copyOf(encryptedData, blockRoundedSize);
		byte[] fullDecrypted = new byte[blockRoundedSize];

		cipher.init(Cipher.DECRYPT_MODE, secretKey);
		cipher.doFinal(blockRoundedEncrypted, 0,
				blockRoundedSize, fullDecrypted);
		return fullDecrypted;
	}
	
	private static byte[] encryptAes(byte[] data, SecretKey secretKey) throws NoSuchAlgorithmException, SignatureException,
	InvalidKeyException, ShortBufferException, IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException {
		Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
		
		int blockRoundedSize = ((data.length + 15) / 16) * 16;
		byte[] blockRoundedData = Arrays.copyOf(data, blockRoundedSize);
		
		cipher.init(Cipher.ENCRYPT_MODE, secretKey);
		return cipher.doFinal(blockRoundedData);
	}
	
	private static SecretKey generateAesKey(byte[] keyData) {
		byte[] aesTruncated = Arrays.copyOf(toSHA1Bytes(keyData), 16);
		System.out.println("AES key data: "+bytesToHex(aesTruncated));
		return new SecretKeySpec(aesTruncated, "AES");
	}
	
	private static byte[] concatBytes(byte[] a, byte[] b) {
		byte[] c = new byte[a.length + b.length];
		System.arraycopy(a, 0, c, 0, a.length);
		System.arraycopy(b, 0, c, a.length, b.length);
		return c;
	}
	
	public PairState getPairState(String uniqueId) throws MalformedURLException, IOException, XmlPullParserException  {
		String serverInfo = http.openHttpConnectionToString(http.baseUrl + "/serverinfo?uniqueid="+uniqueId);
		if (!NvHTTP.getXmlString(serverInfo, "PairStatus").equals("1")) {
			return PairState.NOT_PAIRED;
		}
		
		String pairChallenge = http.openHttpConnectionToString(http.baseUrl + "/pair?uniqueid="+uniqueId+"&devicename=roth&updateState=1&phrase=pairchallenge");
		if (NvHTTP.getXmlString(pairChallenge, "paired").equals("1")) {
			return PairState.PAIRED;
		}
		else {
			return PairState.NOT_PAIRED;
		}
	}
	
	public PairState pair(String uniqueId, String pin) throws MalformedURLException, IOException, XmlPullParserException, CertificateException, InvalidKeyException, NoSuchAlgorithmException, SignatureException, ShortBufferException, IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException {
		// Generate a salt for hashing the PIN
		byte[] salt = generateRandomBytes(16);
		System.out.println("Using salt: "+bytesToHex(salt));

		// Combine the salt and pin, then create an AES key from them
		byte[] saltAndPin = saltPin(salt, pin);
		aesKey = generateAesKey(saltAndPin);
		
		// Send the salt and get the server cert
		String getCert = http.openHttpConnectionToString(http.baseUrl +
				"/pair?uniqueid="+uniqueId+"&devicename=roth&updateState=1&phrase=getservercert&salt="+bytesToHex(salt)+"&clientcert="+bytesToHex(pubKeyBytes));
		if (!NvHTTP.getXmlString(getCert, "paired").equals("1")) {
			return PairState.FAILED;
		}
		X509Certificate serverCert = extractPlainCert(getCert);
		System.out.println(serverCert);
		
		// Generate a random challenge and encrypt it with our AES key
		byte[] randomChallenge = generateRandomBytes(16);
		System.out.println("Unencrypted challenge: "+bytesToHex(randomChallenge));
		byte[] encryptedChallenge = encryptAes(randomChallenge, aesKey);
		System.out.println("Encrypted challenge: "+bytesToHex(encryptedChallenge));
		
		// Send the encrypted challenge to the server
		String challengeResp = http.openHttpConnectionToString(http.baseUrl + 
				"/pair?uniqueid="+uniqueId+"&devicename=roth&updateState=1&clientchallenge="+bytesToHex(encryptedChallenge));
		if (!NvHTTP.getXmlString(challengeResp, "paired").equals("1")) {
			return PairState.FAILED;
		}
		
		// Decode the server's response and subsequent challenge
		byte[] encServerChallengeResponse = hexToBytes(NvHTTP.getXmlString(challengeResp, "challengeresponse"));
		System.out.println("Encrypted challenge response: "+bytesToHex(encServerChallengeResponse));
		byte[] decServerChallengeResponse = decryptAes(encServerChallengeResponse, aesKey);
		System.out.println("Decrypted challenge response: "+bytesToHex(decServerChallengeResponse));
		
		byte[] serverResponse = Arrays.copyOfRange(decServerChallengeResponse, 0, 20);
		byte[] serverChallenge = Arrays.copyOfRange(decServerChallengeResponse, 20, 36);
		System.out.println("Server response: "+bytesToHex(serverResponse));
		System.out.println("Server challenge: "+bytesToHex(serverChallenge));
		
		// Using another 16 bytes secret, compute a challenge response hash using the secret, our cert sig, and the challenge
		byte[] clientSecret = generateRandomBytes(16);
		byte[] challengeRespHash = toSHA1Bytes(concatBytes(concatBytes(serverChallenge, cert.getSignature()), clientSecret));
		System.out.println("Client challenge response hash: "+bytesToHex(challengeRespHash));
		byte[] challengeRespEncrypted = encryptAes(challengeRespHash, aesKey);
		System.out.println("Client challenge response encrypted: "+bytesToHex(challengeRespEncrypted));
		String secretResp = http.openHttpConnectionToString(http.baseUrl +
				"/pair?uniqueid="+uniqueId+"&devicename=roth&updateState=1&serverchallengeresp="+bytesToHex(challengeRespEncrypted));
		if (!NvHTTP.getXmlString(secretResp, "paired").equals("1")) {
			return PairState.FAILED;
		}
		
		// Get the server's signed secret
		byte[] serverSecretResp = hexToBytes(NvHTTP.getXmlString(secretResp, "pairingsecret"));
		byte[] serverSecret = Arrays.copyOfRange(serverSecretResp, 0, 16);
		byte[] serverSignature = Arrays.copyOfRange(serverSecretResp, 16, 272);

		// Ensure the authenticity of the data
		if (!verifySignature(serverSecret, serverSignature, serverCert)) {
			// Cancel the pairing process
			http.openHttpConnectionToString(http.baseUrl + "/unpair?uniqueid="+uniqueId);
			
			// Looks like a MITM
			return PairState.FAILED;
		}
		
		// Ensure the server challenge matched what we expected (aka the PIN was correct)
		byte[] serverChallengeRespHash = toSHA1Bytes(concatBytes(concatBytes(randomChallenge, serverCert.getSignature()), serverSecret));
		System.out.println("Re-calculated server challenge response hash: "+bytesToHex(serverChallengeRespHash));
		System.out.println("Original challenge response: "+bytesToHex(serverResponse));
		if (!Arrays.equals(serverChallengeRespHash, serverResponse)) {
			// Cancel the pairing process
			http.openHttpConnectionToString(http.baseUrl + "/unpair?uniqueid="+uniqueId);
			
			// Probably got the wrong PIN
			return PairState.PIN_WRONG;
		}
		
		// Send the server our signed secret
		byte[] clientPairingSecret = concatBytes(clientSecret, signData(clientSecret, pk));
		System.out.println("Client pairing secret: "+bytesToHex(clientPairingSecret));
		String clientSecretResp = http.openHttpConnectionToString(http.baseUrl + 
				"/pair?uniqueid="+uniqueId+"&devicename=roth&updateState=1&clientpairingsecret="+bytesToHex(clientPairingSecret));
		if (!NvHTTP.getXmlString(clientSecretResp, "paired").equals("1")) {
			return PairState.FAILED;
		}
		
		// Do the initial challenge (seems neccessary for us to show as paired)
		String pairChallenge = http.openHttpConnectionToString(http.baseUrl + "/pair?uniqueid="+uniqueId+"&devicename=roth&updateState=1&phrase=pairchallenge");
		if (!NvHTTP.getXmlString(pairChallenge, "paired").equals("1")) {
			return PairState.FAILED;
		}
		
		return PairState.PAIRED;
	}
}
