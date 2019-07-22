package com.sdrtuner;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class RtlsdrSource implements IQSourceInterface {
	public static final int RTLSDR_TUNER_UNKNOWN 	= 0;
	public static final int RTLSDR_TUNER_E4000 		= 1;
	public static final int RTLSDR_TUNER_FC0012		= 2;
	public static final int RTLSDR_TUNER_FC0013		= 3;
	public static final int RTLSDR_TUNER_FC2580		= 4;
	public static final int RTLSDR_TUNER_R820T		= 5;
	public static final int RTLSDR_TUNER_R828D		= 6;
	public static final String[] TUNER_STRING = {"UNKNOWN", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D"};

	public static final int RTL_TCP_COMMAND_SET_FREQUENCY 	= 0x01;
	public static final int RTL_TCP_COMMAND_SET_SAMPLERATE 	= 0x02;
	public static final int RTL_TCP_COMMAND_SET_GAIN_MODE	= 0x03;
	public static final int RTL_TCP_COMMAND_SET_GAIN 		= 0x04;
	public static final int RTL_TCP_COMMAND_SET_FREQ_CORR 	= 0x05;
	public static final int RTL_TCP_COMMAND_SET_IFGAIN 		= 0x06;
	public static final int RTL_TCP_COMMAND_SET_AGC_MODE 	= 0x08;
	public final String[] COMMAND_NAME = {"invalid", "SET_FREQUENY", "SET_SAMPLERATE", "SET_GAIN_MODE",
			"SET_GAIN", "SET_FREQ_CORR", "SET_IFGAIN", "SET_TEST_MODE", "SET_ADC_MODE"};

	private ReceiverThread receiverThread = null;
	private CommandThread commandThread = null;
	private Callback callback = null;
	private Socket socket = null;
	private InputStream inputStream = null;
	private OutputStream outputStream = null;
	private String name = "RTL-SDR";
	private String magic = null;
	private int tuner = RTLSDR_TUNER_UNKNOWN;
	private String ipAddress = "127.0.0.1";
	private int port = 1234;
	private ArrayBlockingQueue<byte[]> queue = null;
	private ArrayBlockingQueue<byte[]> returnQueue = null;
	private long frequency = 0;
	private int sampleRate = 0;
	private int gain = 0;
	private int ifGain = 0;
	private boolean manualGain = true;	// true == manual; false == automatic
	private int frequencyCorrection = 0;
	private boolean automaticGainControl = false;
	private int frequencyOffset = 0;	// virtually offset the frequency according to an external up/down-converter
	private IQConverter iqConverter;
	private static final String LOGTAG = "RtlsdrSource";
	private static final int QUEUE_SIZE = 20;
	public static final int[] OPTIMAL_SAMPLE_RATES = { 1000000, 1024000, 1800000, 1920000, 2000000, 2048000, 2400000};
	public static final long[] MIN_FREQUENCY = { 0,			// invalid
												52000000l,	// E4000
												22000000l,	// FC0012
												22000000l,	// FC0013
												146000000l,	// FC2580
												24000000l,	// R820T
												24000000l};	// R828D
	public static final long[] MAX_FREQUENCY = { 0l,			// invalid
												3000000000l,	// E4000		actual max freq: 2200000000l
												3000000000l,	// FC0012		actual max freq: 948000000l
												3000000000l,	// FC0013		actual max freq: 1100000000l
												3000000000l,	// FC2580		actual max freq: 924000000l
												3000000000l,	// R820T		actual max freq: 1766000000l
												3000000000l};	// R828D		actual max freq: 1766000000l
	public static final int[][] POSSIBLE_GAIN_VALUES = {	// Values from gr_osmocom rt_tcp_source_s.cc:
			{0},																		// invalid
			{-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420},			// E4000
			{-99, -40, 71, 179, 192},													// FC0012
			{-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68,
					70, 71, 179, 181, 182, 184, 186, 188, 191, 197},					// FC0013
			{0},																		// FC2580
			{0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280,
					297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496},	// R820T
			{0}																			// R828D ??
	};
	public static final int PACKET_SIZE = 16384;

	public RtlsdrSource (String ip, int port) {
		this.ipAddress = ip;
		this.port = port;

		// Create queues and buffers:
		queue = new ArrayBlockingQueue<byte[]>(QUEUE_SIZE);
		returnQueue = new ArrayBlockingQueue<byte[]>(QUEUE_SIZE);
		for(int i = 0; i < QUEUE_SIZE; i++)
			returnQueue.offer(new byte[PACKET_SIZE]);

		this.iqConverter = new Unsigned8BitIQConverter();
	}

	/**
	 * Will forward an error message to the callback object
	 *
	 * @param msg	error message
	 */
	private void reportError(String msg) {
		if(callback != null)
			callback.onIQSourceError(this,msg);
		else
			Log.e(LOGTAG, "reportError: Callback is null. (Error: " + msg + ")");
	}

	/**
	 * This will start the RTL2832U driver app if ip address is loopback and connect to the rtl_tcp instance
	 *
	 * @param context        not used
	 * @param callback       reference to a class that implements the Callback interface for notification
	 * @return
	 */
	@Override
	public boolean open(Context context, Callback callback) {
		this.callback = callback;

		// Start the command thread (this will perform the "open" procedure:
		// connecting to the rtl_tcp instance, read information and inform the callback handler
		if(commandThread != null) {
			Log.e(LOGTAG,"open: Command thread is still running");
			reportError("Error while opening device");
			return false;
		}
		commandThread = new CommandThread();
		commandThread.start();

		return true;
	}

	@Override
	public boolean isOpen() {
		return (commandThread != null);
	}

	@Override
	public boolean close() {
		// Stop receving:
		if(receiverThread != null)
			stopSampling();

		// Stop the command thread:
		if(commandThread != null) {
			commandThread.stopCommandThread();
			// Join the thread only if the current thread is NOT the commandThread ^^
			if(!Thread.currentThread().getName().equals(commandThread.threadName)) {
				try {
					commandThread.join();
				} catch (InterruptedException e) {
				}
			}
			commandThread = null;
		}

		this.tuner = 0;
		this.magic = null;
		this.name = "RTL-SDR";
		return true;
	}

	@Override
	public String getName() {
		return name;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public int getPort() {
		return port;
	}

	@Override
	public int getSampleRate() {
		return sampleRate;
	}

	@Override
	public void setSampleRate(int sampleRate) {
		if(isOpen()) {
			if(sampleRate < getMinSampleRate() || sampleRate > getMaxSampleRate()) {
				Log.e(LOGTAG, "setSampleRate: Sample rate out of valid range: " + sampleRate);
				return;
			}

			if(!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_SAMPLERATE, sampleRate))) {
				Log.e(LOGTAG, "setSampleRate: failed.");
			}
		}

		// Flush the queue:
		this.flushQueue();

		this.sampleRate = sampleRate;
		this.iqConverter.setSampleRate(sampleRate);
	}

	@Override
	public long getFrequency() {
		return frequency + frequencyOffset;
	}

	@Override
	public void setFrequency(long frequency) {
		long actualSourceFrequency = frequency - frequencyOffset;
		if(isOpen()) {
			if(frequency < getMinFrequency() || frequency > getMaxFrequency()) {
				Log.e(LOGTAG, "setFrequency: Frequency out of valid range: " + frequency
								+ "  (upconverterFrequency="+ frequencyOffset +" is subtracted!)");
				return;
			}

			commandThread.executeFrequencyChangeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQUENCY, (int) actualSourceFrequency));
		}

		// Flush the queue:
		this.flushQueue();

		this.frequency = actualSourceFrequency;
		this.iqConverter.setFrequency(frequency);
	}

	@Override
	public long getMaxFrequency() {
		return MAX_FREQUENCY[tuner] + frequencyOffset;
	}

	@Override
	public long getMinFrequency() {
		return MIN_FREQUENCY[tuner] + frequencyOffset;
	}

	@Override
	public int getMaxSampleRate() {
		return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length - 1];
	}

	@Override
	public int getMinSampleRate() {
		return OPTIMAL_SAMPLE_RATES[0];
	}

	@Override
	public int getNextHigherOptimalSampleRate(int sampleRate) {
		for (int opt : OPTIMAL_SAMPLE_RATES) {
			if (sampleRate < opt)
				return opt;
		}
		return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length-1];
	}

	@Override
	public int getNextLowerOptimalSampleRate(int sampleRate) {
		for (int i = 1; i < OPTIMAL_SAMPLE_RATES.length; i++) {
			if(sampleRate <= OPTIMAL_SAMPLE_RATES[i])
				return OPTIMAL_SAMPLE_RATES[i-1];
		}
		return OPTIMAL_SAMPLE_RATES[OPTIMAL_SAMPLE_RATES.length-1];
	}

	@Override
	public int[] getSupportedSampleRates() {
		return OPTIMAL_SAMPLE_RATES;
	}

	public boolean isManualGain() {
		return manualGain;
	}

	public void setManualGain(boolean enable) {
		if(isOpen()) {
			if(!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN_MODE, (int)(enable ? 0x01 : 0x00)))) {
				Log.e(LOGTAG, "setManualGain: failed.");
			}
		}
		this.manualGain = enable;
	}

	public int getGain() {
		return gain;
	}

	public int[] getPossibleGainValues() {
		return POSSIBLE_GAIN_VALUES[tuner];
	}

	public void setGain(int gain) {
		if(isOpen()) {
			if(!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN, gain))) {
				Log.e(LOGTAG, "setGain: failed.");
			}
		}
		this.gain = gain;
	}

	public int getIFGain() {
		return ifGain;
	}

	public int[] getPossibleIFGainValues() {
		if(tuner == RTLSDR_TUNER_E4000) {
			int[] ifGainValues = new int[54];
			for(int i = 0; i < ifGainValues.length; i++)
				ifGainValues[i] = i + 3;
			return ifGainValues;
		} else {
			return new int[] {0};
		}
	}

	public void setIFGain(int ifGain) {
		if(isOpen() && tuner == RTLSDR_TUNER_E4000) {
			if(!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_IFGAIN, (short) 0, (short) ifGain))) {
				Log.e(LOGTAG, "setIFGain: failed.");
			}
		}
		this.ifGain = ifGain;
	}

	public int getFrequencyCorrection() {
		return frequencyCorrection;
	}

	public void setFrequencyCorrection(int ppm) {
		if(isOpen()) {
			if(!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQ_CORR, ppm))) {
				Log.e(LOGTAG, "setFrequencyCorrection: failed.");
			}
		}
		this.frequencyCorrection = ppm;
	}

	public boolean isAutomaticGainControl() {
		return automaticGainControl;
	}

	public void setAutomaticGainControl(boolean enable) {
		if (isOpen()) {
			if (!commandThread.executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_AGC_MODE, (int)(enable ? 0x01 : 0x00)))) {
				Log.e(LOGTAG, "setAutomaticGainControl: failed.");
			}
		}
		this.automaticGainControl = enable;
	}

	public int getFrequencyOffset() {
		return frequencyOffset;
	}

	public void setFrequencyOffset(int frequencyShift) {
		this.frequencyOffset = frequencyShift;
		this.iqConverter.setFrequency(frequency + frequencyShift);
	}

	@Override
	public int getPacketSize() {
		return PACKET_SIZE;
	}

	@Override
	public byte[] getPacket(int timeout) {
		if(queue != null) {
			try {
				return queue.poll(timeout, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "getPacket: Interrupted while polling packet from queue: " + e.getMessage());
			}
		} else {
			Log.e(LOGTAG, "getPacket: Queue is null");
		}
		return null;
	}

	@Override
	public void returnPacket(byte[] buffer) {
		if(returnQueue != null) {
			returnQueue.offer(buffer);
		} else {
			Log.e(LOGTAG, "returnPacket: Return queue is null");
		}
	}

	@Override
	public void startSampling() {
		if(receiverThread != null) {
			Log.e(LOGTAG, "startSampling: receiver thread still running.");
			reportError("Could not start sampling");
			return;
		}

		if(isOpen()) {
			// start ReceiverThread:
			receiverThread = new ReceiverThread(inputStream, returnQueue, queue);
			receiverThread.start();
		}
	}

	@Override
	public void stopSampling() {
		// stop and join receiver thread:
		if(receiverThread != null) {
			receiverThread.stopReceiving();
			// Join the thread only if the current thread is NOT the receiverThread ^^
			if(!Thread.currentThread().getName().equals(receiverThread.threadName)) {
				try {
					receiverThread.join();
				} catch (InterruptedException e) {
					Log.e(LOGTAG, "stopSampling: Interrupted while joining receiver thread: " + e.getMessage());
				}
			}
			receiverThread = null;
		}
	}

	@Override
	public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
		return this.iqConverter.fillPacketIntoSamplePacket(packet, samplePacket);
	}

	@Override
	public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
		return this.iqConverter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency);
	}

	/**
	 * Will empty the queue
	 */
	public void flushQueue() {
		byte[] buffer;

		for (int i = 0; i < QUEUE_SIZE; i++) {
			buffer = queue.poll();
			if(buffer == null)
				return; // we are done; the queue is empty.
			this.returnPacket(buffer);
		}
	}

	/**
	 * Will pack a rtl_tcp command into a byte buffer
	 *
	 * @param command			RTL_TCP_COMMAND_*
	 * @param arg				command argument (see rtl_tcp documentation)
	 * @return command buffer
	 */
	private byte[] commandToByteArray(int command, int arg) {
		byte[] commandArray = new byte[5];
		commandArray[0] = (byte) command;
		commandArray[1] = (byte) ((arg >> 24) & 0xff);
		commandArray[2] = (byte) ((arg >> 16) & 0xff);
		commandArray[3] = (byte) ((arg >> 8) & 0xff);
		commandArray[4] = (byte) (arg & 0xff);
		return commandArray;
	}

	/**
	 * Will pack a rtl_tcp command into a byte buffer
	 *
	 * @param command			RTL_TCP_COMMAND_*
	 * @param arg1				first command argument (see rtl_tcp documentation)
	 * @param arg2				second command argument (see rtl_tcp documentation)
	 * @return command buffer
	 */
	private byte[] commandToByteArray(int command, short arg1, short arg2) {
		byte[] commandArray = new byte[5];
		commandArray[0] = (byte) command;
		commandArray[1] = (byte) ((arg1 >> 8) & 0xff);
		commandArray[2] = (byte) (arg1 & 0xff);
		commandArray[3] = (byte) ((arg2 >> 8) & 0xff);
		commandArray[4] = (byte) (arg2 & 0xff);
		return commandArray;
	}

	/**
	 * This thread will read samples from the socket and put them in the queue
	 */
	private class ReceiverThread extends Thread {
		public String threadName = null;	// We save the thread name to check against it in the stopSampling() method
		private boolean stopRequested = false;
		private InputStream inputStream = null;
		private ArrayBlockingQueue<byte[]> inputQueue = null;
		private ArrayBlockingQueue<byte[]> outputQueue = null;

		public ReceiverThread(InputStream inputStream, ArrayBlockingQueue<byte[]> inputQueue, ArrayBlockingQueue<byte[]> outputQueue) {
			this.inputStream 	= inputStream;
			this.inputQueue 	= inputQueue;
			this.outputQueue 	= outputQueue;
		}

		public void stopReceiving() {
			this.stopRequested = true;
		}

		public void run() {
			byte[] buffer = null;
			int index = 0;
			int bytesRead = 0;

			Log.i(LOGTAG, "ReceiverThread started (Thread: " + this.getName() + ")");
			threadName = this.getName();

			while(!stopRequested) {
				try {
					// if buffer is null we request a new buffer from the inputQueue:
					if(buffer == null) {
						buffer = inputQueue.poll(1000, TimeUnit.MILLISECONDS);
						index = 0;
					}

					if(buffer == null) {
						Log.e(LOGTAG, "ReceiverThread: Couldn't get buffer from input queue. stop.");
						this.stopRequested = true;
						break;
					}

					// Read into the buffer from the inputStream:
					bytesRead = inputStream.read(buffer, index, buffer.length - index);

					if(bytesRead <= 0) {
						Log.e(LOGTAG, "ReceiverThread: Couldn't read data from input stream. stop.");
						this.stopRequested = true;
						break;
					}

					index += bytesRead;
					if(index == buffer.length) {
						// buffer is full. Send it to the output queue:
						outputQueue.offer(buffer);
						buffer = null;
					}

				} catch (InterruptedException e) {
					Log.e(LOGTAG, "ReceiverThread: Interrupted while waiting: " + e.getMessage());
					this.stopRequested = true;
					break;
				} catch (IOException e) {
					Log.e(LOGTAG, "ReceiverThread: Error while reading from socket: " + e.getMessage());
					reportError("Error while receiving samples.");
					this.stopRequested = true;
					break;
				} catch (NullPointerException e) {
					Log.e(LOGTAG, "ReceiverThread: Nullpointer! (Probably inputStream): " + e.getStackTrace());
					this.stopRequested = true;
					break;
				}
			}
			// check if we still hold a buffer and return it to the input queue:
			if(buffer != null)
				inputQueue.offer(buffer);

			Log.i(LOGTAG, "ReceiverThread stopped (Thread: " + this.getName() + ")");
		}
	}

	/**
	 * This thread will initiate the connection to the rtl_tcp instance and then send commands to
	 * it. Commands can be queued for execution by other threads
	 */
	private class CommandThread extends Thread {
		public String threadName = null;	// We save the thread name to check against it in the close() method
		private ArrayBlockingQueue<byte[]> commandQueue = null;
		private static final int COMMAND_QUEUE_SIZE = 20;
		private ArrayBlockingQueue<byte[]> frequencyChangeCommandQueue = null;	// separate queue for frequency changes (work-around)
		private boolean stopRequested = false;

		public CommandThread() {
			// Create command queue:
			this.commandQueue = new ArrayBlockingQueue<byte[]>(COMMAND_QUEUE_SIZE);
			this.frequencyChangeCommandQueue = new ArrayBlockingQueue<byte[]>(1);	// work-around
		}

		public void stopCommandThread() {
			this.stopRequested = true;
		}

		/**
		 * Will schedule the command (put it into the command queue
		 *
		 * @param command	5 byte command array (see rtl_tcp documentation)
		 * @return true if command has been scheduled;
		 */
		public boolean executeCommand(byte[] command) {
			Log.d(LOGTAG,"executeCommand: Queuing command: " +COMMAND_NAME[command[0]]);
			if(commandQueue.offer(command))
				return true;

			// Queue is full
			// todo: maybe flush the queue? for now just error:
			Log.e(LOGTAG, "executeCommand: command queue is full!");
			return false;
		}

		/**
		 * Work-around:
		 * Frequency changes happen very often and if too many of these commands are sent to the driver
		 * it will lag and eventually crash. To prevent this, we have a separate commandQueue only for
		 * frequency changes. This queue has size 1 and executeFrequencyChangeCommand() will ensure that
		 * it contains always the latest frequency change command. The command thread will always sleep 250 ms
		 * after executing a frequency change command to prevent a high rate of commands.
		 *
		 * @param command	5 byte command array (see rtl_tcp documentation)
		 */
		public void executeFrequencyChangeCommand(byte[] command) {
			// remove any waiting frequency change command from the queue (not used any more):
			frequencyChangeCommandQueue.poll();
			frequencyChangeCommandQueue.offer(command);	// will always work
		}

		/**
		 * Called from run(); will setup the connection to the rtl_tcp instance
		 */
		private boolean connect(int timeoutMillis) {
			if(socket != null) {
				Log.e(LOGTAG,"connect: Socket is still connected");
				return false;
			}

			// Connect to remote/local rtl_tcp
			try {
				long timeoutTime = System.currentTimeMillis() + timeoutMillis;
				while(!stopRequested && socket == null && System.currentTimeMillis() < timeoutTime) {
					try {
						socket = new Socket(ipAddress, port);
					} catch (IOException e) {
						// ignore...
					}
					sleep(100);
				}

				if(socket == null) {
					if(stopRequested)
						Log.i(LOGTAG, "CommandThread: (connect) command thread stopped while connecting.");
					else
						Log.e(LOGTAG, "CommandThread: (connect) hit timeout");
					return false;
				}

				// Set socket options:
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(1000);

				inputStream = socket.getInputStream();
				outputStream = socket.getOutputStream();
				byte[] buffer = new byte[4];

				// Read magic value:
				if(inputStream.read(buffer, 0, buffer.length) != buffer.length) {
					Log.e(LOGTAG,"CommandThread: (connect) Could not read magic value");
					return false;
				}
				magic = new String(buffer, "ASCII");

				// Read tuner type:
				if(inputStream.read(buffer, 0, buffer.length) != buffer.length) {
					Log.e(LOGTAG,"CommandThread: (connect) Could not read tuner type");
					return false;
				}
				tuner = buffer[3];
				if(tuner <= 0 || tuner >= TUNER_STRING.length) {
					Log.e(LOGTAG,"CommandThread: (connect) Invalid tuner type");
					return false;
				}

				// Read gain count (only for debugging. value is not used for now)
				if(inputStream.read(buffer, 0, buffer.length) != buffer.length) {
					Log.e(LOGTAG,"CommandThread: (connect) Could not read gain count");
					return false;
				}

				Log.i(LOGTAG,"CommandThread: (connect) Connected to RTL-SDR (Tuner: " + TUNER_STRING[tuner] + ";  magic: " + magic +
						";  gain count: " + buffer[3] + ") at " + ipAddress + ":" + port);

				// Update source name with the new information:
				name = "RTL-SDR (" + TUNER_STRING[tuner] + ")";// + ipAddress + ":" + port;

				// Check if parameters are in range and correct them:
				if(frequency > MAX_FREQUENCY[tuner]) {
					frequency = MAX_FREQUENCY[tuner];
				}
				if(frequency < MIN_FREQUENCY[tuner]) {
					frequency = MIN_FREQUENCY[tuner];
				}
				iqConverter.setFrequency(frequency + frequencyOffset);
				if(sampleRate > getMaxSampleRate())
					sampleRate = getMaxSampleRate();
				if(sampleRate < getMinSampleRate())
					sampleRate = getMinSampleRate();
				for(int gainStep: getPossibleGainValues()) {
					if (gainStep >= gain) {
						gain = gainStep;
						break;
					}
				}

				// Set all parameters:
				// Frequency:
				executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQUENCY, (int)frequency));

				// Sample Rate:
				executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_SAMPLERATE, sampleRate));

				// Gain Mode:
				executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN_MODE, (int)(manualGain ? 0x01 : 0x00)));

				// Gain:
				if(manualGain)
					executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_GAIN, gain));

				// IFGain:
				if(manualGain && tuner == RTLSDR_TUNER_E4000)
					executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_IFGAIN, (short)0, (short)ifGain));

				// Frequency Correction:
				executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_FREQ_CORR, frequencyCorrection));

				// AGC mode:
				executeCommand(commandToByteArray(RTL_TCP_COMMAND_SET_AGC_MODE, (int)(automaticGainControl ? 0x01 : 0x00)));

				return true;

			} catch (UnknownHostException e) {
				Log.e(LOGTAG,"CommandThread: (connect) Unknown host: " + ipAddress);
				reportError("Unknown host: " + ipAddress);
			} catch (IOException e) {
				Log.e(LOGTAG,"CommandThread: (connect) Error while connecting to rtlsdr://" + ipAddress + ":" + port + " : " + e.getMessage());
			} catch (InterruptedException e) {
				Log.e(LOGTAG,"CommandThread: (connect) Interrupted.");
			}
			return false;
		}

		public void run() {
			Log.i(LOGTAG, "CommandThread started (Thread: " + this.getName() + ")");
			threadName = this.getName();
			byte[] nextCommand = null;

			// Perfom "device open". This means connect to the rtl_tcp instance; get the information
			if(connect(10000)) {	// 10 seconds for the user to accept permission request
				// report that the device is ready:
				callback.onIQSourceReady(RtlsdrSource.this);
			} else {
				if(!stopRequested) {
					Log.e(LOGTAG, "CommandThread: (open) connect reported error.");
					reportError("Couldn't connect to rtl_tcp instance");
					stopRequested = true;
				}
				// else: thread was stopped while connecting...
			}

			// poll commands from queue and send them over the socket in loop:
			while(!stopRequested && outputStream != null) {
				try {
					nextCommand = commandQueue.poll(100, TimeUnit.MILLISECONDS);

					// Work-around:
					// Frequency changes happen very often and if too many of these commands are sent to the driver
					// it will lag and eventually crash. To prevent this, we have a separate commandQueue only for
					// frequency changes. This queue has size 1 and executeFrequencyChangeCommand() will ensure that
					// it contains always the latest frequency change command. The command thread will always sleep 100 ms
					// after executing a frequency change command to prevent a high rate of commands.
					if(nextCommand == null)
						nextCommand = frequencyChangeCommandQueue.poll(); // check for frequency change commands:

					if(nextCommand == null)
						continue;
					outputStream.write(nextCommand);
					Log.d(LOGTAG,"CommandThread: Command was sent: " + COMMAND_NAME[nextCommand[0]]);
				} catch (IOException e) {
					Log.e(LOGTAG, "CommandThread: Error while sending command (" + COMMAND_NAME[nextCommand[0]] + "): " + e.getMessage());
					reportError("Error while sending command: " + COMMAND_NAME[nextCommand[0]]);
					break;
				} catch (InterruptedException e) {
					Log.e(LOGTAG, "CommandThread: Interrupted while sending command (" + COMMAND_NAME[nextCommand[0]] + ")");
					reportError("Interrupted while sending command: " + COMMAND_NAME[nextCommand[0]]);
					break;
				}
			}

			// Clean up:
			if(socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			socket = null;
			inputStream = null;
			outputStream = null;
			RtlsdrSource.this.commandThread = null;		// mark this source as 'closed'
			Log.i(LOGTAG, "CommandThread stopped (Thread: " + this.getName() + ")");
		}
	}
}
