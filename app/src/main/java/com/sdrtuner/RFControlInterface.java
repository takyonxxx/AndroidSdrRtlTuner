package com.sdrtuner;

public interface RFControlInterface {

	/**
	 * Is called to adjust the demodulation mode
	 *
	 * @param newDemodulationMode	new demodulation mode: DEMODULATION_OFF, DEMODULATION_AM, ...
	 *                              (see Demodulator)
	 * @return true if success, false if analyzer not running
	 */
	public boolean updateDemodulationMode(int newDemodulationMode);

	/**
	 * Is called to adjust the channel width.
	 *
	 * @param newChannelWidth	new channel width (single sided) in Hz
	 * @return true if valid width; false if width is out of range
	 * not able to set channel width because scheduler not running
	 */
	public boolean updateChannelWidth(int newChannelWidth);

	/**
	 * Is called to adjust the channel frequency.
	 *
	 * @param newChannelFrequency	new channel frequency in Hz
	 * @return true if success, false if analyzer not running
	 */
	public boolean updateChannelFrequency(long newChannelFrequency);

	/**
	 * Is called to adjust the frequency of the signal source.
	 *
	 * @param newSourceFrequency	new source frequency in Hz
	 * @return true if success, false if source not running
	 */
	public boolean updateSourceFrequency(long newSourceFrequency);

	/**
	 * Is called to adjust the sample rate of the signal source.
	 *
	 * @param newSampleRate			new sample rate in Sps
	 * @return true if success, false if source not running
	 */
	public boolean updateSampleRate(int newSampleRate);

	/**
	 * Is called to adjust the squelch level
	 *
	 * @param newSquelch	new squelch level
	 */
	public void updateSquelch(float newSquelch);

	/**
	 * Is called when the signal strength of the selected channel
	 * crosses the squelch threshold
	 *
	 * @param squelchSatisfied	true: the signal is now stronger than the threshold; false: signal is now weaker
	 * @return true if success, false if scheduler not running
	 */
	public boolean updateSquelchSatisfied(boolean squelchSatisfied);

	/**
	 * Is called to determine the current channel width
	 *
	 * @return	the current channel width
	 */
	public int requestCurrentChannelWidth();

	/**
	 * Is called to determine the current channel frequency
	 *
	 * @return	the current channel frequency
	 */
	public long requestCurrentChannelFrequency();

	/**
	 * Is called to determine the current demodulation mode
	 * @return the current demodulation mode (Demodulator.DEMODULATION_OFF, *_AM, *_FM, ...)
	 */
	public int requestCurrentDemodulationMode();

	/**
	 * Is called to determine the current squelch setting
	 * @return the current squelch setting (in dB)
	 */
	public float requestCurrentSquelch();

	/**
	 * Is called to determine the current source frequency
	 *
	 * @return	the current frequency of the signal source
	 */
	public long requestCurrentSourceFrequency();

	/**
	 * Is called to determine the current sample rate of the signal source
	 *
	 * @return	the current sample rate of the signal source
	 */
	public int requestCurrentSampleRate();

	/**
	 * Is called to determine the maximum source frequency
	 *
	 * @return	the maximum frequency of the signal source
	 */
	public long requestMaxSourceFrequency();

	/**
	 * Is called to determine the sample rates supported by the signal source
	 *
	 * @return	array of all supported sample rates
	 */
	public int[] requestSupportedSampleRates();
}
