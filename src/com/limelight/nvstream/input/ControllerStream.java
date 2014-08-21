package com.limelight.nvstream.input;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import com.limelight.nvstream.NvConnectionListener;

public class ControllerStream {
	
	public final static int PORT = 35043;
	
	public final static int CONTROLLER_TIMEOUT = 3000;
	
	private InetAddress host;
	private Socket s;
	private OutputStream out;
	private Cipher riCipher;
	private NvConnectionListener listener;
	
	private Thread inputThread;
	private LinkedBlockingQueue<InputPacket> inputQueue = new LinkedBlockingQueue<InputPacket>();
	
	public ControllerStream(InetAddress host, SecretKey riKey, int riKeyId, NvConnectionListener listener)
	{
		this.host = host;
		this.listener = listener;
		try {
			// This cipher is guaranteed to be supported
			this.riCipher = Cipher.getInstance("AES/CBC/NoPadding");
			
			ByteBuffer bb = ByteBuffer.allocate(16);
			bb.putInt(riKeyId);
			
			this.riCipher.init(Cipher.ENCRYPT_MODE, riKey, new IvParameterSpec(bb.array()));
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (NoSuchPaddingException e) {
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			e.printStackTrace();
		} catch (InvalidAlgorithmParameterException e) {
			e.printStackTrace();
		}
	}
	
	public void initialize() throws IOException
	{
		s = new Socket();
		s.connect(new InetSocketAddress(host, PORT), CONTROLLER_TIMEOUT);
		s.setTcpNoDelay(true);
		out = s.getOutputStream();
	}
	
	public void start()
	{
		inputThread = new Thread() {
			@Override
			public void run() {
				while (!isInterrupted()) {
					InputPacket packet;
					
					try {
						packet = inputQueue.take();
					} catch (InterruptedException e) {
						listener.connectionTerminated(e);
						return;
					}
					
					// Try to batch mouse move packets
					if (!inputQueue.isEmpty() && packet instanceof MouseMovePacket) {
						MouseMovePacket initialMouseMove = (MouseMovePacket) packet;
						int totalDeltaX = initialMouseMove.deltaX;
						int totalDeltaY = initialMouseMove.deltaY;
						
						// Combine the deltas with other mouse move packets in the queue
						synchronized (inputQueue) {
							Iterator<InputPacket> i = inputQueue.iterator();
							while (i.hasNext()) {
								InputPacket queuedPacket = i.next();
								if (queuedPacket instanceof MouseMovePacket) {
									MouseMovePacket queuedMouseMove = (MouseMovePacket) queuedPacket;
									
									// Add this packet's deltas to the running total
									totalDeltaX += queuedMouseMove.deltaX;
									totalDeltaY += queuedMouseMove.deltaY;
									
									// Remove this packet from the queue
									i.remove();
								}
							}
						}
						
						// Total deltas could overflow the short so we must split them if required
						do {
							short partialDeltaX = (short)(totalDeltaX < 0 ?
									Math.max(Short.MIN_VALUE, totalDeltaX) :
									Math.min(Short.MAX_VALUE, totalDeltaX));
							short partialDeltaY = (short)(totalDeltaY < 0 ?
									Math.max(Short.MIN_VALUE, totalDeltaY) :
									Math.min(Short.MAX_VALUE, totalDeltaY));
							
							initialMouseMove.deltaX = partialDeltaX;
							initialMouseMove.deltaY = partialDeltaY;
							
							try {
								sendPacket(initialMouseMove);
							} catch (IOException e) {
								listener.connectionTerminated(e);
								return;
							}
							
							totalDeltaX -= partialDeltaX;
							totalDeltaY -= partialDeltaY;
						} while (totalDeltaX != 0 && totalDeltaY != 0);
					}
					// Try to batch axis changes on controller packets too
					else if (!inputQueue.isEmpty() && packet instanceof ControllerPacket) {
						ControllerPacket initialControllerPacket = (ControllerPacket) packet;
						ControllerBatchingBlock batchingBlock = null;
						
						synchronized (inputQueue) {
							Iterator<InputPacket> i = inputQueue.iterator();
							while (i.hasNext()) {
								InputPacket queuedPacket = i.next();
								
								if (queuedPacket instanceof ControllerPacket) {
									// Only initialize the batching block if we got here
									if (batchingBlock == null) {
										batchingBlock = new ControllerBatchingBlock(initialControllerPacket);
									}
									
									if (batchingBlock.submitNewPacket((ControllerPacket) queuedPacket))
									{
										// Batching was successful, so remove this packet
										i.remove();
									}
									else
									{
										// Unable to batch so we must stop
										break;
									}
								}
							}
						}
						
						if (batchingBlock != null) {
							// Reinitialize the initial packet with the new values
							batchingBlock.reinitializePacket(initialControllerPacket);
						}
						
						try {
							sendPacket(packet);
						} catch (IOException e) {
							listener.connectionTerminated(e);
							return;
						}
					}
					else {
						// Send any other packet as-is
						try {
							sendPacket(packet);
						} catch (IOException e) {
							listener.connectionTerminated(e);
							return;
						}
					}
				}
			}
		};
		inputThread.setName("Input - Queue");
		inputThread.start();
	}
	
	public void abort()
	{
		if (inputThread != null) {
			inputThread.interrupt();
			
			try {
				inputThread.join();
			} catch (InterruptedException e) {}
		}
		
		try {
			s.close();
		} catch (IOException e) {}
	}
	
	private static int getPaddedSize(int length) {
		return ((length + 15) / 16) * 16;
	}
	
	private static byte[] padData(byte[] data) {
		// This implements the PKCS7 padding algorithm
		
		if ((data.length % 16) == 0) {
			// Already a multiple of 16
			return data;
		}
		
		byte[] padded = Arrays.copyOf(data, getPaddedSize(data.length));
		byte paddingByte = (byte)(16 - (data.length % 16));
		
		for (int i = data.length; i < padded.length; i++) {
			padded[i] = paddingByte;
		}
		
		return padded;
	}
	
	private byte[] encryptAesInputData(byte[] data) throws Exception {
		return riCipher.update(padData(data));
	}
	
	private void sendPacket(InputPacket packet) throws IOException {
		byte[] toWire = packet.toWire();
		
		// Pad to 16 byte chunks
		int paddedLength = getPaddedSize(toWire.length);
		
		// Allocate a byte buffer to represent the final packet
		ByteBuffer bb = ByteBuffer.allocate(4 + paddedLength);
		bb.putInt(paddedLength);
		try {
			bb.put(encryptAesInputData(toWire));
		} catch (Exception e) {
			// Should never happen
			e.printStackTrace();
			return;
		}
		
		// Send the packet
		out.write(bb.array());
		out.flush();
	}
	
	private void queuePacket(InputPacket packet) {
		synchronized (inputQueue) {
			inputQueue.add(packet);
		}
	}
	
	public void sendControllerInput(short buttonFlags, byte leftTrigger, byte rightTrigger,
			short leftStickX, short leftStickY, short rightStickX, short rightStickY)
	{
		queuePacket(new ControllerPacket(buttonFlags, leftTrigger,
				rightTrigger, leftStickX, leftStickY,
				rightStickX, rightStickY));
	}
	
	public void sendMouseButtonDown(byte mouseButton)
	{
		queuePacket(new MouseButtonPacket(true, mouseButton));
	}
	
	public void sendMouseButtonUp(byte mouseButton)
	{
		queuePacket(new MouseButtonPacket(false, mouseButton));
	}
	
	public void sendMouseMove(short deltaX, short deltaY)
	{
		queuePacket(new MouseMovePacket(deltaX, deltaY));
	}
	
	public void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier) 
	{
		queuePacket(new KeyboardPacket(keyMap, keyDirection, modifier));
	}
}
