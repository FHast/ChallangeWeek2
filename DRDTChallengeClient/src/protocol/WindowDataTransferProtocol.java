package protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.zip.CRC32;

import client.Utils;

public class WindowDataTransferProtocol extends IRDTProtocol {
	static final int DATASIZE = 256; // max. number of user data bytes in each
										// packet
	static final int HEADERSIZE = 2; // number of header bytes in each packet
	static final int PACKETDELAY = 500;
	static final int TIMEOUT = 15000;

	// window parameters
	static int LFS = -1;
	static int LAR = -1;
	static int RWS = 16;
	static int SWS = 16;
	static int LFR = -1;
	static int LAF = LFR + RWS;
	static int k = 256;
	static int filePointer = 0;
	static int windowCounter = 0;

	static ArrayList<Integer> receivedAck = new ArrayList<>();
	static HashMap<Integer, Integer[]> receivedPkt = new HashMap<>();

	@Override
	public void TimeoutElapsed(Object tag) {
		if ((int) tag == windowCounter) {
			System.out.println("TIMEOUT!");
			sendWindow();
		}
	}

	@Override
	public void sender() {
		System.out.println("Sending...");

		// read from the input file
		Integer[] fileContents = Utils.getFileContents(getFileID());

		// send initial window
		sendWindow();

		boolean stop = false;
		while (!stop) {
			// try to receive a packet from the network layer
			Integer[] packet = getNetworkLayer().receivePacket();

			// check for acknowledgements
			if (packet != null) {
				if (packet[0] > LAR) {
					System.out.println("Acknowledgement received with sequencenumber=" + packet[0]);
					receivedAck.add(packet[0]);
					while (receivedAck.contains((LAR + 1) % k)) {
						receivedAck.remove((Object) ((LAR + 1) % k));
						LAR++;
						LAR = LAR % k;
						filePointer += DATASIZE;
					}
				}

				if (LAR == LFS) {
					// window completely send, next window
					windowCounter++;
					sendWindow();
				}

				if (filePointer > fileContents.length) {
					System.out.println("all packets send!");
				}
			}

			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				stop = true;
			}
		}
	}

	public int getFilePointer(int sn) {
		return sn * DATASIZE;
	}

	public Integer[] getPacket(int sequenceNumber) {
		filePointer = getFilePointer(sequenceNumber);
		// getting the data from the file for the packet with this sequence
		// number
		Integer[] fileContents = Utils.getFileContents(getFileID());
		int length = Math.min(DATASIZE, fileContents.length - filePointer);
		Integer[] pkt = new Integer[HEADERSIZE + length];
		pkt[0] = sequenceNumber;
		pkt[1] = 0;
		pkt[2] = 0;
		System.out.println("Packet length: " + length);
		System.arraycopy(fileContents, filePointer, pkt, HEADERSIZE, length);
		return pkt;
	}

	public void sendPacket(int sequencenumber) {
		// sending the packet with this sequence number
		Integer[] pkt = getPacket(sequencenumber);
		appendChecksum(pkt);
		getNetworkLayer().sendPacket(pkt);
		System.out.println(
				"Sent one packet with sequencenumber=" + pkt[0] + ", Packet number: " + filePointer / DATASIZE);
	}

	public int getChecksum(Integer[] packet) {
		// calculating checksum
		int total = 0;
		for (int i = HEADERSIZE; i < packet.length; i++) {
			total += packet[i];
		}
		return total % 256;
	}

	public void appendChecksum(Integer[] packet) {
		// add checksum to header
		packet[1] = getChecksum(packet);
	}

	public void sendWindow() {

		// sending a full window
		Integer[] fileContents = Utils.getFileContents(getFileID());

		System.out.println("sending window... LAR: " + LAR + " | SWS: " + SWS);
		int current = filePointer;

		int i = (LAR + 1) % k;
		filePointer = getFilePointer(i);
		int wi = 0;
		while (wi < SWS) {
			if (filePointer < fileContents.length) {
				if (!receivedAck.contains((i + LAR + 1) % k)) {
					sendPacket((i + LAR + 1) % k);
					try {
						Thread.sleep(PACKETDELAY);
					} catch (InterruptedException e) {
						// do nothing
					}
					wi++;
					wi = wi % k;
				}
				filePointer += DATASIZE;
				i++;
				i = i % k;
			} else {
				break;
			}
		}
		LFS = wi - 1;
		filePointer = current;

		client.Utils.Timeout.SetTimeout(TIMEOUT, this, windowCounter);
	}

	@Override
	public void receiver() {
		System.out.println("Receiving...");
		int lastsequencenumber = -2;

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
				// check if corrupted
				if (packet[1] == getChecksum(packet)) {

					if (packet.length != (HEADERSIZE + DATASIZE)) {
						System.out.println("last packet: " + packet[0]);
						lastsequencenumber = packet[0];
					}

					// check if packet was already received
					if (!receivedPkt.containsKey(packet[0])) {
						Integer[] pktWithoutHeader = new Integer[packet.length - HEADERSIZE];
						System.arraycopy(packet, HEADERSIZE, pktWithoutHeader, 0, packet.length - HEADERSIZE);
						receivedPkt.put(packet[0], pktWithoutHeader);
					} else {
					}

					// send acknowledgement
					Integer[] ack = new Integer[] { packet[0] };
					getNetworkLayer().sendPacket(ack);

					// tell the user
					System.out.println("Received packet, length=" + packet.length + "  sequencenumber=" + packet[0]);

					// update LFR
					int oldlength = 0;
					int datalen = 0;
					while (receivedPkt.containsKey((LFR + 1) % k)) {
						System.out.println("adding " + ((LFR + 1) % k) + " to file contents...");
						oldlength = fileContents.length;
						datalen = receivedPkt.get((LFR + 1) % k).length;
						fileContents = Arrays.copyOf(fileContents, oldlength + datalen);
						System.arraycopy(receivedPkt.get((LFR + 1) % k), 0, fileContents, oldlength, datalen);
						receivedPkt.remove((LFR + 1) % k);
						LFR++;
						LFR = LFR % k;
					}

					// file complete
					if (LFR == lastsequencenumber) {
						System.out.println("END");
						stop = true;
					}
				} else {
					System.out.println("corrupted! sequencenumber: " + packet[0]);
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
