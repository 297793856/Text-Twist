import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

/*
 * conditionally thread safe: words
 */
public class SRUDP_Client extends SRUDP implements Callable<Boolean> {
	protected static int FIXED_SIZE;
	protected static String ID_PREFIX = "_";
	private static final int TIMEOUT = 1 * 100;
	private static final int MAX_TIMEOUTS = 15;

	private String client_name;
	private List<String> words;
	private LocalDateTime endTime; // max time willing to exchange data

	/**
	 * 
	 * @param server server's name
	 * @param port server's port
	 * @param name client's name
	 * @param words list of messages to send
	 * @param id_pref a String to be used as a prefix of name
	 */
	public SRUDP_Client(String server, int port, String name, List<String> words, String id_pref) {
		super(server, port);
		ID_PREFIX = id_pref;
		this.client_name = ID_PREFIX + name;
		this.words = words;
		this.endTime = LocalDateTime.MAX;
		FIXED_SIZE = SRUDP_Server.FIXED_SIZE;
	}

	/**
	 * 
	 * @param timeout_s the connection must end before timeout_s seconds from the creation of the object
	 * 			0 for no timeout
	 * 			timeout_s isn't considered if all the messages are sent before it elapses
	 */
	public void setTimeout(long timeout_s) {
		if (timeout_s > 0)
			endTime = LocalDateTime.now().plusSeconds(timeout_s);
		else
			endTime = LocalDateTime.MAX;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Boolean call() {
		DatagramPacket request, receive;
		SocketAddress server;
		Pair<String, String> receivedPair = null, oldPair = null;
		Thread.currentThread().setName("SRUDP Client " + client_name);
		String word = this.client_name;
		byte[] msg, clientByte = Pair.ToByteArray(new Pair<String, String>(this.client_name, "" + words.size()));
		boolean start = false, done = false, result = false;
		int i = 0;
		int timeouts = 0;
		try (DatagramSocket sk = new DatagramSocket();) {
			sk.setSoTimeout(TIMEOUT);
			server = new InetSocketAddress(InetAddress.getByName(server_name), port);

			while (!done) {
				// spin
				try {
					if (start && words.size() == 0) {
						done = true;
						continue;
					}
					// if still pairing
					else if (!start)
						msg = clientByte;
					else {
						// send / get the word
						word = words.get(i);
						msg = Pair.ToByteArray(new Pair<String, String>(this.client_name, word));
					}

					request = new DatagramPacket(msg, msg.length, server);
					sk.send(request);

					receive = new DatagramPacket(new byte[request.getLength()], request.getLength());
					sk.receive(receive);
					receivedPair = (Pair<String, String>) Pair.FromByteArray(receive.getData());
					if (receivedPair == null) {
						++timeouts;
					} else if (!start && word.equals(receivedPair.v1))
						start = true;
					else if (start && word.equals(receivedPair.v2)) {
						done = ++i == words.size();
						timeouts = 0;
						oldPair = receivedPair;
					} else if (start && oldPair != null && word.equals(oldPair.v2))
						;
					done = done || hasTimedout();
				} catch (SocketTimeoutException e) {
					done = done || ++timeouts == MAX_TIMEOUTS;
				}
			}
			result = timeouts != MAX_TIMEOUTS;
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {

		}
		return result;
	}

	public boolean hasTimedout() {
		return LocalDateTime.now().isAfter(endTime);
	}

}
