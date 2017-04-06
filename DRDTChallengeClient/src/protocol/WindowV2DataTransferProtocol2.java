package protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.zip.CRC32;

import client.Utils;

public class WindowV2DataTransferProtocol2 extends IRDTProtocol {
	static final int DATASIZE = 64; // max. number of user data bytes in each
									// packet
	static final int HEADERSIZE = 3; // number of header bytes in each packet
	static final int PACKETDELAY = 100;
	static final int TIMEOUT = 5000;

	// window parameters
	static int LFS = -1;
	static int LAR = -1;
	static int RWS = 64;
	static int SWS = 64;
	static int LFR = -1;
	static int LAF = LFR + RWS;
	static int k = 256;
	static int filePointer = 0;
	static int windowCounter = 0;
	static int rounds = 0;

	static ArrayList<Integer> receivedAck = new ArrayList<>();
	static HashMap<Integer, Integer[]> receivedPkt = new HashMap<>();

	@Override
	public void TimeoutElapsed(Object tag) {
		if ((int) tag == windowCounter) {
			System.out.println("TIMEOUT");
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
				if (packet[0] > LAR || packet[1] == windowCounter) {
					System.out.println("Acknowledgement received for sequencenumber= " + packet[0]);
					receivedAck.add(packet[0]);

					int index = (LAR + 1) % k;
					while (receivedAck.contains((Object) index)) {
						LAR = index;
						receivedAck.remove((Object) index);
					}

					if (LFS == LAR) {
						sendWindow();
					}
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

	public Integer[] getPacket(int packetnumber) {
		filePointer = getFilePointer(packetnumber);
		System.out.println("position in file: " + filePointer);
		Integer[] fileContents = Utils.getFileContents(getFileID());
		int length = Math.min(DATASIZE, fileContents.length - filePointer);
		Integer[] pkt = new Integer[HEADERSIZE + length];
		pkt[0] = packetnumber % k;
		pkt[1] = 0;
		pkt[2] = windowCounter;
		System.out.println("Packet length: " + length);
		System.arraycopy(fileContents, filePointer, pkt, HEADERSIZE, length);
		return pkt;
	}

	public void sendPacket(int packetnumber) {
		// sending the packet with this sequence number
		Integer[] pkt = getPacket(packetnumber);
		appendChecksum(pkt);
		getNetworkLayer().sendPacket(pkt);
		System.out.println("Sent one packet with sequencenumber=" + packetnumber % k + ", Packet number: "
				+ packetnumber);
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
		Integer[] fileContents = Utils.getFileContents(getFileID());
		windowCounter++;
		int i = (LAR + 1) % k;
		int packetssend = 0;
		while (packetssend < SWS && i < (LAR + k / 2)) {
			filePointer = getFilePointer(i);
			if (filePointer < fileContents.length) {
				if (!receivedAck.contains(i % k)) {
					sendPacket((i % k) + rounds * k);
					try {
						Thread.sleep(PACKETDELAY);
					} catch (InterruptedException e) {
						// do nothing
					}
					packetssend++;
				}
				i++;
				if (i >= k) {
					rounds ++;
				}
				i = i % k;
			} else {
				break;
			}
		}
		LFS = i - 1;

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
					Integer[] ack = new Integer[] { packet[0], packet[2] };
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
