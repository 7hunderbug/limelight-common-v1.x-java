package com.limelight.nvstream;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.xmlpull.v1.XmlPullParserException;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioStream;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.av.video.VideoStream;
import com.limelight.nvstream.control.ControlStream;
import com.limelight.nvstream.http.GfeHttpResponseException;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.input.NvController;
import com.limelight.nvstream.rtsp.RtspConnection;

public class NvConnection {
	private String host;
	private NvConnectionListener listener;
	private StreamConfiguration config;
	private LimelightCryptoProvider cryptoProvider;
	
	private InetAddress hostAddr;
	private ControlStream controlStream;
	private NvController inputStream;
	private VideoStream videoStream;
	private AudioStream audioStream;
	
	// Start parameters
	private int drFlags;
	private Object videoRenderTarget;
	private VideoDecoderRenderer videoDecoderRenderer;
	private AudioRenderer audioRenderer;
	private String localDeviceName;
	private SecretKey riKey;
	
	private ThreadPoolExecutor threadPool;
	
	public NvConnection(String host, NvConnectionListener listener, StreamConfiguration config, LimelightCryptoProvider cryptoProvider)
	{
		this.host = host;
		this.listener = listener;
		this.config = config;
		this.cryptoProvider = cryptoProvider;
		
		try {
			// This is unique per connection
			this.riKey = generateRiAesKey();
		} catch (NoSuchAlgorithmException e) {
			// Should never happen
			e.printStackTrace();
		}
		
		this.threadPool = new ThreadPoolExecutor(1, 1, Long.MAX_VALUE, TimeUnit.DAYS,
				new LinkedBlockingQueue<Runnable>(), new ThreadPoolExecutor.DiscardPolicy());
	}
	
	private static SecretKey generateRiAesKey() throws NoSuchAlgorithmException {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		
		// RI keys are 128 bits
		keyGen.init(128);
		
		return keyGen.generateKey();
	}
	
	public static String getMacAddressString() throws SocketException {
		Enumeration<NetworkInterface> ifaceList;
		NetworkInterface selectedIface = null;

		// First look for a WLAN interface (since those generally aren't removable)
		ifaceList = NetworkInterface.getNetworkInterfaces();
		while (selectedIface == null && ifaceList.hasMoreElements()) {
			NetworkInterface iface = ifaceList.nextElement();

			if (iface.getName().startsWith("wlan") && 
				iface.getHardwareAddress() != null &&
				iface.getHardwareAddress().length > 0) {
				selectedIface = iface;
			}
		}

		// If we didn't find that, look for an Ethernet interface
		ifaceList = NetworkInterface.getNetworkInterfaces();
		while (selectedIface == null && ifaceList.hasMoreElements()) {
			NetworkInterface iface = ifaceList.nextElement();

			if (iface.getName().startsWith("eth") &&
				iface.getHardwareAddress() != null &&
				iface.getHardwareAddress().length > 0) {
				selectedIface = iface;
			}
		}
		
		// Now just find something with a MAC address
		ifaceList = NetworkInterface.getNetworkInterfaces();
		while (selectedIface == null && ifaceList.hasMoreElements()) {
			NetworkInterface iface = ifaceList.nextElement();

			if (iface.getHardwareAddress() != null &&
				iface.getHardwareAddress().length > 0) {
				selectedIface = iface;
				break;
			}
		}
		
		if (selectedIface == null) {
			return null;
		}

		byte[] macAddress = selectedIface.getHardwareAddress();
		if (macAddress != null) {
			StringBuilder addrStr = new StringBuilder();
			for (int i = 0; i < macAddress.length; i++) {
				addrStr.append(String.format("%02x", macAddress[i]));
				if (i != macAddress.length - 1) {
					addrStr.append(':');
				}
			}
			return addrStr.toString();
		}
		
		return null;
	}
	
	public void stop()
	{
		threadPool.shutdownNow();
		
		if (videoStream != null) {
			videoStream.abort();
		}
		if (audioStream != null) {
			audioStream.abort();
		}
		
		if (controlStream != null) {
			controlStream.abort();
		}
		
		if (inputStream != null) {
			inputStream.close();
			inputStream = null;
		}
	}
	
	private boolean startSteamBigPicture() throws XmlPullParserException, IOException
	{
		NvHTTP h = new NvHTTP(hostAddr, getMacAddressString(), localDeviceName, cryptoProvider);
		
		if (h.getServerVersion().startsWith("1.")) {
			listener.displayMessage("Limelight now requires GeForce Experience 2.0.1 or later. Please upgrade GFE on your PC and try again.");
			return false;
		}
		
		if (h.getPairState() != PairingManager.PairState.PAIRED) {
			listener.displayMessage("Device not paired with computer");
			return false;
		}
		
		NvApp app = h.getSteamApp();
		if (app == null) {
			listener.displayMessage("Steam not found in GFE app list");
			return false;
		}
		
		// If there's a game running, resume it
		if (h.getCurrentGame() != 0) {
			try {
				if (!h.resumeApp(riKey)) {
					listener.displayMessage("Failed to resume existing session");
					return false;
				}
			} catch (GfeHttpResponseException e) {
				if (e.getErrorCode() == 470) {
					// This is the error you get when you try to resume a session that's not yours.
					// Because this is fairly common, we'll display a more detailed message.
					listener.displayMessage("This session wasn't started by this device," +
							" so it cannot be resumed. End streaming on the original " +
							"device or the PC itself and try again. (Error code: "+e.getErrorCode()+")");
					return false;
				}
				else {
					throw e;
				}
			}
			LimeLog.info("Resumed existing game session");
		}
		else {
			// Launch the app since it's not running
			int gameSessionId = h.launchApp(app.getAppId(), config.getWidth(),
					config.getHeight(), config.getRefreshRate(), riKey);
			if (gameSessionId == 0) {
				listener.displayMessage("Failed to launch application");
				return false;
			}
			LimeLog.info("Launched new game session");
		}
		
		return true;
	}
	
	private boolean doRtspHandshake() throws IOException
	{
		RtspConnection r = new RtspConnection(hostAddr);
		r.doRtspHandshake(config);
		return true;
	}
	
	private boolean startControlStream() throws IOException
	{
		controlStream = new ControlStream(hostAddr, listener);
		controlStream.initialize();
		controlStream.start();
		return true;
	}
	
	private boolean startVideoStream() throws IOException
	{
		videoStream = new VideoStream(hostAddr, listener, controlStream, config);
		return videoStream.startVideoStream(videoDecoderRenderer, videoRenderTarget, drFlags);
	}
	
	private boolean startAudioStream() throws IOException
	{
		audioStream = new AudioStream(hostAddr, listener, audioRenderer);
		return audioStream.startAudioStream();
	}
	
	private boolean startInputConnection() throws IOException
	{
		// Because input events can be delivered at any time, we must only assign
		// it to the instance variable once the object is properly initialized.
		// This avoids the race where inputStream != null but inputStream.initialize()
		// has not returned yet.
		NvController tempController = new NvController(hostAddr, riKey);
		tempController.initialize();
		inputStream = tempController;
		return true;
	}
	
	private void establishConnection() {
		for (NvConnectionListener.Stage currentStage : NvConnectionListener.Stage.values())
		{
			boolean success = false;

			listener.stageStarting(currentStage);
			try {
				switch (currentStage)
				{
				case LAUNCH_APP:
					success = startSteamBigPicture();
					break;

				case RTSP_HANDSHAKE:
					success = doRtspHandshake();
					break;
					
				case CONTROL_START:
					success = startControlStream();
					break;
					
				case VIDEO_START:
					success = startVideoStream();
					break;
					
				case AUDIO_START:
					success = startAudioStream();
					break;
					
				case INPUT_START:
					success = startInputConnection();
					break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				listener.displayMessage(e.getMessage());
				success = false;
			}
			
			if (success) {
				listener.stageComplete(currentStage);
			}
			else {
				listener.stageFailed(currentStage);
				return;
			}
		}
		
		listener.connectionStarted();
	}

	public void start(String localDeviceName, Object videoRenderTarget, int drFlags, AudioRenderer audioRenderer, VideoDecoderRenderer videoDecoderRenderer)
	{
		this.localDeviceName = localDeviceName;
		this.drFlags = drFlags;
		this.audioRenderer = audioRenderer;
		this.videoRenderTarget = videoRenderTarget;
		this.videoDecoderRenderer = videoDecoderRenderer;
		
		new Thread(new Runnable() {
			public void run() {
				try {
					hostAddr = InetAddress.getByName(host);
				} catch (UnknownHostException e) {
					listener.connectionTerminated(e);
					return;
				}
				
				establishConnection();
			}
		}).start();
	}
	
	public void sendMouseMove(final short deltaX, final short deltaY)
	{
		if (inputStream == null)
			return;
		
		threadPool.execute(new Runnable() {
			public void run() {
				try {
					inputStream.sendMouseMove(deltaX, deltaY);
				} catch (IOException e) {
					listener.connectionTerminated(e);
				}
			}
		});
	}
	
	public void sendMouseButtonDown(final byte mouseButton)
	{
		if (inputStream == null)
			return;
		
		threadPool.execute(new Runnable() {
			public void run() {
				try {
					inputStream.sendMouseButtonDown(mouseButton);
				} catch (IOException e) {
					listener.connectionTerminated(e);
				}
			}
		});
	}
	
	public void sendMouseButtonUp(final byte mouseButton)
	{
		if (inputStream == null)
			return;
		
		threadPool.execute(new Runnable() {
			public void run() {
				try {
					inputStream.sendMouseButtonUp(mouseButton);
				} catch (IOException e) {
					listener.connectionTerminated(e);
				}
			}
		});
	}
	
	public void sendControllerInput(final short buttonFlags,
			final byte leftTrigger, final byte rightTrigger,
			final short leftStickX, final short leftStickY,
			final short rightStickX, final short rightStickY)
	{
		if (inputStream == null)
			return;
		
		threadPool.execute(new Runnable() {
			public void run() {
				try {
					inputStream.sendControllerInput(buttonFlags, leftTrigger,
							rightTrigger, leftStickX, leftStickY,
							rightStickX, rightStickY);
				} catch (IOException e) {
					listener.connectionTerminated(e);
				}
			}
		});
	}
	
	public void sendKeyboardInput(final short keyMap, final byte keyDirection, final byte modifier) {
		if (inputStream == null)
			return;
		
		threadPool.execute(new Runnable() {
			public void run() {
				try {
					inputStream.sendKeyboardInput(keyMap, keyDirection, modifier);
				} catch (IOException e) {
					listener.connectionTerminated(e);
				}
			}
		});
	}
}
