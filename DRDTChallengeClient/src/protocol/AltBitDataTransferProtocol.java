package protocol;

import java.util.Arrays;

import client.Utils;

public class AltBitDataTransferProtocol extends IRDTProtocol {
	static final int HEADERSIZE = 1; // number of header bytes in each packet
	static final int DATASIZE = 256; // max. number of user data bytes in each
										// packet
	static final int TIMEOUT = 10000;
	static int LFS = -1;
	static int filePointer = 0;

	@Override
	public void TimeoutElapsed(Object tag) {
		if ((int) tag == filePointer) {
			System.out.println("TIMOUT! packet number: " + filePointer / 256);
			sendPacket(LFS);
		}
	}

	@Override
	public void sender() {
		System.out.println("Sending...");

		// read from the input file
		Integer[] fileContents = Utils.getFileContents(getFileID());

		// window parameters
		int LAR = -1;
		int SWS = 1;
		int k = 2;

		sendPacket(0);

		// and loop and sleep; you may use this loop to check for incoming
		// acks...
		boolean stop = false;
		while (!stop) {
			// try to receive a packet from the network layer
			Integer[] packet = getNetworkLayer().receivePacket();

			// if we indeed received a packet
			if (packet != null) {
				System.out.println("Acknowledgement received with sequencenumber=" + packet[0]);
				if (packet[0] != LAR) {
					LAR = packet[0];
					filePointer += DATASIZE;
				}

				if (filePointer > fileContents.length) {
					System.out.println("all packets send!");
				} else {
					// send next packet
					sendPacket((LAR + 1) % 2);
				}
			}

			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				stop = true;
			}
		}
	}

	public Integer[] getPacket(int sequenceNumber) {
		Integer[] fileContents = Utils.getFileContents(getFileID());

		int length = Math.min(DATASIZE, fileContents.length - filePointer);
		Integer[] pkt = new Integer[HEADERSIZE + length];

		pkt[0] = sequenceNumber;
		System.arraycopy(fileContents, filePointer, pkt, HEADERSIZE, length);

		return pkt;
	}

	public void sendPacket(int sequencenumber) {
		Integer[] pkt = getPacket(sequencenumber);
		getNetworkLayer().sendPacket(pkt);

		System.out.println("Sent one packet with sequencenumber=" + pkt[0] + ", Packet number: " + filePointer / 256);

		LFS = sequencenumber;

		// set timeout again
		client.Utils.Timeout.SetTimeout(TIMEOUT, this, filePointer);
	}

	@Override
	public void receiver() {
		System.out.println("Receiving...");

		// create the array that will contain the file contents
		// note: we don't know yet how large the file will be, so the easiest
		// (but not most efficient)
		// is to reallocate the array every time we find out there's more data
		Integer[] fileContents = new Integer[0];

		// loop until we are done receiving the file
		boolean stop = false;
		while (!stop) {

			// try to receive a packet from the network layer
			Integer[] packet = getNetworkLayer().receivePacket();

			// if we indeed received a packet
			if (packet != null) {

				// send acknowledgement
				Integer[] ack = new Integer[] { packet[0] };
				getNetworkLayer().sendPacket(ack);

				// tell the user
				System.out.println("Received packet, length=" + packet.length + "  sequencenumber=" + packet[0]);

				// append the packet's data part (excluding the header) to the
				// fileContents array, first making it larger
				int oldlength = fileContents.length;
				int datalen = packet.length - HEADERSIZE;
				fileContents = Arrays.copyOf(fileContents, oldlength + datalen);
				System.arraycopy(packet, HEADERSIZE, fileContents, oldlength, datalen);

				// and let's just hope the file is now complete
				if (packet.length != HEADERSIZE + DATASIZE) {
					stop = true;
				}

			} else {
				// wait ~10ms (or however long the OS makes us wait) before
				// trying again
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					stop = true;
				}
			}
		}

		// write to the output file
		Utils.setFileContents(fileContents, getFileID());
	}

}
