package com.sdrtuner;

public abstract class IQConverter {
	protected long frequency = 0;						// Baseband frequency of the converted samples (is put into the SamplePacket)
	protected int sampleRate = 0;						// Sample rate of the converted samples (is put into the SamplePacket)
	protected float[] lookupTable = null;				// Lookup table to transform IQ bytes into doubles
	protected float[][] cosineRealLookupTable = null;	// Lookup table to transform IQ bytes into frequency shifted doubles
	protected float[][] cosineImagLookupTable = null;	// Lookup table to transform IQ bytes into frequency shifted doubles
	protected int cosineFrequency;						// Frequency of the cosine that is mixed to the signal
	protected int cosineIndex;							// current index within the cosine
	protected static final int MAX_COSINE_LENGTH = 500;	// Max length of the cosine lookup table

	public IQConverter() {
		generateLookupTable();
	}

	public long getFrequency() {
		return frequency;
	}

	public void setFrequency(long frequency) {
		this.frequency = frequency;
	}

	public int getSampleRate() {
		return sampleRate;
	}

	public void setSampleRate(int sampleRate) {
		if(this.sampleRate != sampleRate) {
			this.sampleRate = sampleRate;
			generateMixerLookupTable(cosineFrequency);
		}
	}

	protected int calcOptimalCosineLength() {
		// look for the best fitting array size to hold one or more full cosine cycles:
		double cycleLength = sampleRate / Math.abs((double)cosineFrequency);
		int bestLength = (int) cycleLength;
		double bestLengthError = Math.abs(bestLength-cycleLength);
		for (int i = 1; i*cycleLength < MAX_COSINE_LENGTH ; i++) {
			if(Math.abs(i*cycleLength - (int)(i*cycleLength)) < bestLengthError) {
				bestLength = (int)(i*cycleLength);
				bestLengthError = Math.abs(bestLength - (i*cycleLength));
			}
		}
		return bestLength;
	}

	public abstract int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket);

	public abstract int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency);

	protected abstract void generateLookupTable();

	protected abstract void generateMixerLookupTable(int mixFrequency);


}
