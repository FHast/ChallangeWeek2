package protocol;

import client.DRDTChallengeClient;
import client.NetworkLayer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Entry point of the program. Starts the client and links the used MAC
 * protocol.
 *
 * @author Jaco ter Braak & Frans van Dijk, Twente University
 * @version 10-02-2016
 */
public class Program {

	// Change to your group number (use your student number)
	private static int groupId = 1776592;

	// Change to your group password (doesn't matter what it is,
	// as long as everyone in the group uses the same string)
	private static String password = "password123";

	// Choose ID of test file to transmit: 1, 2, 3, 4, 5
	// Sizes in bytes are: 248, 2085, 6267, 21067, 53228
	private static int file = 5;

	// Change to your protocol implementation
	private static IRDTProtocol protocolImpl = new WindowV2DataTransferProtocol2();

	// Challenge server address
	private static String serverAddress = "netsys.student.utwente.nl";

	// Challenge server port
	private static int serverPort = 8003;

	/*
	 *
	 *
	 *
	 *
	 *
	 *
	 *
	 * DO NOT EDIT BELOW THIS LINE
	 */
	public static void main(String[] args) {
		int group = groupId;

		if (args.length > 0) {
			group = Integer.parseInt(args[0]);
		}

		DRDTChallengeClient client = null;
		try {
			System.out.print("[FRAMEWORK] Starting client... ");

			// Create the client
			client = new DRDTChallengeClient(serverAddress, serverPort, group, password);

			System.out.println("Done.");

			System.out.println("[FRAMEWORK] Press Enter to start the simulation as sender...");
			System.out.println("[FRAMEWORK] (Simulation will be started automatically as receiver " +
												"when the other client in the group issues the start command)");

			boolean startRequested = false;
			InputStream inputStream = new BufferedInputStream(System.in);
			while (!client.isSimulationStarted() && !client.isSimulationFinished()) {
				if (!startRequested && inputStream.available() > 0) {
					// Request start as sender.
					client.requestStart(file);
					startRequested = true;
				}
				Thread.sleep(10);
			}

			System.out.println("[FRAMEWORK] Simulation started!");

			protocolImpl.setNetworkLayer(new NetworkLayer(client));
			protocolImpl.setFileID(client.getFileID());
			if (startRequested) {
				System.out.println("[FRAMEWORK] Running protocol implementation as sender...");
				protocolImpl.sender();
			} else {
				System.out.println("[FRAMEWORK] Running protocol implementation as receiver...");
				protocolImpl.receiver();
			}

		} catch (IOException e) {
			System.out.println("[FRAMEWORK] Could not start the client, because: ");
			e.printStackTrace();
		} catch (InterruptedException e) {
			System.out.println("[FRAMEWORK] Operation interrupted.");
			e.printStackTrace();
		} catch (Exception e) {
			System.out.println("[FRAMEWORK] Unexpected Exception: ");
			e.printStackTrace();
		} finally {
			if (client != null) {
				System.out.print("[FRAMEWORK] Flushing output buffer... ");
				while (!client.isOutputBufferEmpty()) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						break;
					}
				}
				System.out.println("Done.");
				System.out.print("[FRAMEWORK] Shutting down client... ");
				client.stop();
				System.out.println("Done.");
			}
			System.out.println("[FRAMEWORK] Terminating program.");
		}
	}
}
