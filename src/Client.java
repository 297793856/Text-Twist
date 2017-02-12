import java.util.List;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Client {
	private static final int ACTION_LOGIN = 1, ACTION_SIGNUP = 2, ACTION_LOGOUT = 3, ACTION_BACK = 4, ACTION_QUIT = 5,
			ACTION_VIEW_INVITES = 6, ACTION_ACCEPT_INVITE = 7, ACTION_SEND_INVITE = 8, ACTION_SCOREBOARD = 9;

	private static final int NO_INVITE = -1;
	private static final long INPUT_WAITING_MSEC = 1 * 1000;

	private static int TIME_OPEN_GAME_MIN = 5;
	private static int TIME_TYPING_WORDS_MIN = 1;

	private static String ID_PREFIX = "_";
	private static String configFile;
	private static String SERVER_NAME;
	private static int TCP_PORT;

	private static int UDP_PORT;
	private static long MAX_MINUTES_PENDING_INVITE;
	private static int MIN_PLAYERS_PER_MATCH;
	private static int MIN_PASSWORD_LENGTH;
	private static String SERVER_MULTICAST;
	private static int SERVER_MULTICAST_PORT;
	private static int MAX_PACKET_SIZE;
	private static boolean NOTIFY_INVITE;

	private IClient client = null;
	private IServer server = null;
	private boolean loggedIn;
	private boolean quit;
	private String gameLetters = null;

	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			System.err.println("Usage: ./prog \"\\path\\to\\config\\file\"");
			return;
		}
		configFile = args[0];

		if (!(new File(configFile).exists())) {
			System.err.println("The config file does not exist");
			return;
		}

		init();

		(new Client()).exec();
	}

	private void exec() {
		Thread.currentThread().setName("Client");
		int result;

		try {

			do {
				server = (IServer) LocateRegistry.getRegistry().lookup(IServer.REMOTE_OBJECT_NAME);
				result = initServer();
				if (quit)
					break;
				if (result != ACTION_LOGOUT) {
					loggedIn = true;
					quit = false;
					System.out.println("Logged in!");

					result = play();
				} else
					logout();
			} while (result != ACTION_QUIT);

		} catch (RemoteException e) {
			e.printStackTrace();
		} catch (NotBoundException e) {
			e.printStackTrace();
			return;
		} catch (NumberFormatException e) {
			e.printStackTrace();
		} finally {
			try {
				UnicastRemoteObject.unexportObject(client, true);
			} catch (NoSuchObjectException e1) {
			}
			if (server != null && loggedIn && client != null)
				;
			System.out.println("Logged out");
			client = null;
			System.exit(0);
		}
	}

	private int play() {
		int result = ACTION_VIEW_INVITES;
		int invite = -1;
		boolean done = false, askInput = true;
		ArrayList<Integer> availableOptions = new ArrayList<>();
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

		availableOptions.clear();
		availableOptions.add(ACTION_LOGOUT);
		availableOptions.add(ACTION_QUIT);
		availableOptions.add(ACTION_VIEW_INVITES);
		availableOptions.add(ACTION_SEND_INVITE);
		availableOptions.add(ACTION_SCOREBOARD);
		while (!done) {
			try {
				result = ACTION_QUIT;
				invite = NO_INVITE;
				quit = false;

				// requesting an action
				askInput = true;
				do {
					try {
						System.out.println("[" + ACTION_LOGOUT + "] logout\t" + "[" + ACTION_QUIT + "] quit\t" + "["
								+ ACTION_VIEW_INVITES + "] view pending invites\t" + "[" + ACTION_SEND_INVITE
								+ "] send an invite\t" + "[" + ACTION_SCOREBOARD + "] scoreboard");
						askInput = !availableOptions.contains(result = Integer.parseInt(in.readLine()));
					} catch (Exception e) {
					}
				} while (askInput);

				// dispatch
				switch (result) {
				case ACTION_LOGOUT:
					logout();
					done = true;
					break;
				// trying to quit gracefully anyway (don't like ALT-F4 :)
				case ACTION_QUIT:
					logout();
					quit = done = true;
					break;
				case ACTION_SCOREBOARD:
					request_scores();
					result = -1;
					break;
				case ACTION_VIEW_INVITES:
					invite = show_invites();
					if (invite != NO_INVITE) {
						Invite inv;
						List<Invite> invites = client.getInvites();

						synchronized (invites) {
							inv = invites.get(invite);
						}

						result = accept_invite(inv);
						if (result == ServerGameSetupHandler.SERVER_INVITE_ALL_READY) {
							// will listen for the scores
							new Thread(new ClientScoresListener(inv.id(), inv.to().size())).start();
							start_game(inv);
						}
					}
					quit = false;
					break;
				case ACTION_SEND_INVITE:
					send_invite();
					break;
				default:
					break;
				}
				if (quit)
					break;
			} catch (NumberFormatException | IOException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		}

		if (quit)
			return result;
		return -1;
	}

	private void request_scores() throws ClassNotFoundException {
		boolean success;
		try (Socket socket = new Socket(InetAddress.getByName(SERVER_NAME), TCP_PORT);
				ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
				ObjectInputStream in = new ObjectInputStream(socket.getInputStream());) {
			/*
			 * PROTOCOL: out[ACTION: get scores] -> in[success] -> in[scores]
			 */
			out.writeInt(ServerGameSetupHandler.SERVER_SCOREBOARD);
			out.flush();

			success = (Integer) in.readInt() == ServerGameSetupHandler.SERVER_SCOREBOARD;
			if (success) {
				@SuppressWarnings("unchecked")
				Map<String, String> scores = (Map<String, String>) in.readObject();
				for (Map.Entry<String, String> e : scores.entrySet())
					System.out.println(e.getKey() + " has a score of " + e.getValue() + " points");
			} else
				System.err.println("Unknown error\n");
		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("Did you start the server?\n");
		}
	}

	private void start_game(Invite invite) throws IOException {
		boolean canPlay = true;
		BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
		System.out.println("You have " + TIME_TYPING_WORDS_MIN + " min from when you start; "
				+ "the game will be open for " + TIME_OPEN_GAME_MIN + " min from now");
		System.out.println("Press enter to start..");

		// server's time
		LocalDateTime gameStartTime = LocalDateTime.now();
		String input = null;

		while (isGameOpen(LocalDateTime.now(), gameStartTime) && input == null)
			if (stdin.ready())
				input = stdin.readLine();
			else
				try {
					Thread.sleep(INPUT_WAITING_MSEC);
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}

		// user's time
		LocalDateTime playerStartTime = LocalDateTime.now();

		Set<String> words = new TreeSet<>();
		List<String> wordsList = new ArrayList<>();

		canPlay = isGameOpen(playerStartTime, gameStartTime);
		if (canPlay) {
			System.out.println("Type each word on its own line");
			System.out.println("The letters you can play with are: " + gameLetters.toLowerCase());
		} else {
			System.err.println("The game has expired");
		}
		while (canPlay) {
			canPlay = isGameOpen(playerStartTime, gameStartTime);
			try {
				if (canPlay && stdin.ready()) {
					words.add(stdin.readLine().trim().toLowerCase());
				}
				// it's buffered anyway
				Thread.sleep(INPUT_WAITING_MSEC);
			} catch (Exception e) {
			}
		}
		if (!gameStartTime.isBefore(LocalDateTime.now().minusMinutes(Client.TIME_OPEN_GAME_MIN))) {
			System.out.println("You typed");
			for (String s : words)
				System.out.println("\t" + s);
			
			wordsList.addAll(words);
			words.forEach((word) -> {
				if (word.length() < 1 || word.equals(System.lineSeparator())) 
					wordsList.remove(word); 
			});

			System.out.println("Sending words..");

		} else {
			System.err.println("Your gametime has expired" + System.lineSeparator());
		}

		send_words(wordsList, invite.id(), isGameOpen(playerStartTime, gameStartTime));
	}

	private void send_words(List<String> words, int inviteID, boolean gameOpen) {
		ExecutorService ex = Executors.newSingleThreadExecutor();
		Future<Boolean> result = null;
		long timeout = 5;
		try {
			SRUDP_Client srudp = new SRUDP_Client(SERVER_NAME, UDP_PORT + inviteID, client.getName(), words, ID_PREFIX);
			if (words.size() > 0 && gameOpen)
				srudp.setTimeout(timeout = 60 * TIME_OPEN_GAME_MIN);
			else
				srudp.setTimeout(timeout = 5);
			result = ex.submit(srudp);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
		try {
			if (result != null && result.get(timeout, TimeUnit.SECONDS))
				System.out.println("Words sent and successfully received");
			else if (words.size() > 0)
				System.err.println("Words not correctly sent or received" + System.lineSeparator());
		} catch (InterruptedException e) {
			e.printStackTrace();
			System.err.println("Interrupted while sending" + System.lineSeparator());
		} catch (ExecutionException e) {
			e.printStackTrace();
			System.err.println("Thread failed while sending" + System.lineSeparator());
		} catch (TimeoutException e) {
			// game expired
		}
	}

	private int accept_invite(Invite invite) throws ClassNotFoundException, RemoteException {
		int code = ServerGameSetupHandler.SERVER_INVITE_EXPIRED;
		List<Invite> invites = client.getInvites();

		try (Socket socket = new Socket(InetAddress.getByName(SERVER_NAME), TCP_PORT);
				ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
				ObjectInputStream in = new ObjectInputStream(socket.getInputStream());) {
			System.out.println("Accepting invite from " + invite.from());
			/*
			 * PROTOCOL: out[ACTION: accept invites] -> out[from] -> out[invite]
			 * in[game ready] -> in[letters]
			 */
			out.writeInt(ServerGameSetupHandler.SERVER_INVITE_ACCEPT);
			out.writeObject(client.getName());
			out.writeObject(invite);

			System.out.println("Waiting for the other players");
			code = (Integer) in.readInt();
			switch (code) {
			case ServerGameSetupHandler.SERVER_INVITE_ALL_READY:
				System.out.println("Everyone has accepted, the match will now start");
				gameLetters = Utils.StringNoDuplicates((String) in.readObject());
				break;
			case ServerGameSetupHandler.SERVER_INVITE_EXPIRED:
				// also: if someone disconnected in the meantime
				System.err.println("Not everyone has accepted");
				break;
			default:
				System.err.println("Unrecognized code: " + code + System.lineSeparator());
				break;
			}

		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("Did you start the server?" + System.lineSeparator());
		} finally {
			// clearing the user's invites
			synchronized (invites) {
				invites.clear();
			}
		}
		return code;
	}

	private int initServer() throws NotBoundException {
		ArrayList<Integer> availableOptions = new ArrayList<>();

		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		String input, tmp;

		int result = SIGNUP_ERRS.E_NAME;
		boolean success = false;

		availableOptions.add(ACTION_LOGIN);
		availableOptions.add(ACTION_SIGNUP);
		availableOptions.add(ACTION_QUIT);
		while (!success) {
			try {
				do {
					System.out.println("[" + ACTION_LOGIN + "]: login\t[" + ACTION_SIGNUP + "]: register\t["
							+ ACTION_QUIT + "] quit");
				} while (!availableOptions.contains(result = Integer.parseInt(in.readLine())));
				if (result == ACTION_QUIT) {
					quit = true;
					break;
				}

				do {
					System.out.println("Enter a valid username (chars and/or digits): ");
					input = in.readLine();
				} while (!ServerRemote.isNameValid(input));

				do {
					System.out.println("Enter your password (length >= " + MIN_PASSWORD_LENGTH + "): ");
					tmp = in.readLine();
				} while (tmp.length() < MIN_PASSWORD_LENGTH);

				client = new ClientRemote(input, tmp, NOTIFY_INVITE);
				tmp = ""; // clear password from memory!!
				IClient clientStub = (IClient) UnicastRemoteObject.exportObject(client, 0);

				switch (result) {
				case ACTION_LOGIN:
					success = loggedIn = server.login(client.getName(), client.getPassw(), client);
					if (!success)
						System.err.println("Login failed" + System.lineSeparator());
					break;
				case ACTION_SIGNUP:
					result = server.signup(client.getName(), client.getPassw());
					if (result != SIGNUP_ERRS.E_OK)
						System.err.println("Signup failed: " + SIGNUP_ERRS.toStr(result) + System.lineSeparator());
					else
						System.out.println("Now you can login!");
					break;
				case ACTION_QUIT:
					quit = true;
					break;
				default:
					break;
				}
			} catch (IOException | NumberFormatException e) {
			}
		}
		return result;
	}

	private void send_invite() throws IOException {
		System.out.println(
				"To? (comma separated list of usernames; at least " + MIN_PLAYERS_PER_MATCH + " players per match)");
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String input = br.readLine();
		boolean success;
		// N > 0 number of usernames needed to send the invite
		// (and if at least one, the name should be valid)
		// the client should provide at least one other player's username
		if (input.length() > 0 && Character.isLetterOrDigit(input.charAt(0))
				&& !input.trim().equals(client.getName())) {
			System.out.println("Sending invite..");
			try (Socket socket = new Socket(InetAddress.getByName(SERVER_NAME), TCP_PORT);
					ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
					ObjectInputStream in = new ObjectInputStream(socket.getInputStream());) {
				/*
				 * PROTOCOL: out[ACTION: send invites] -> out[from] ->
				 * out[names] in[success]
				 */
				out.writeInt(ServerGameSetupHandler.SERVER_INVITE_SEND);
				out.writeObject(client.getName());
				out.writeObject(input.split(","));

				success = (Integer) in.readInt() != ServerGameSetupHandler.SERVER_INVITE_ERROR;
				if (success)
					System.out.println("Invite sent");
				else
					System.err.println("Error (is everyone online?)" + System.lineSeparator());
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Did you start the server?" + System.lineSeparator());
			}
		}

	}

	private int show_invites() throws RemoteException {
		boolean hasChosen = false;
		int result = -1, nTotal;
		int invite = 0;
		List<Invite> invites;
		ArrayList<Integer> options = new ArrayList<>();
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

			options.add(ACTION_BACK);
			options.add(ACTION_ACCEPT_INVITE);
			do {
				invite = 0;
				LocalDateTime expired = LocalDateTime.now().minusMinutes(MAX_MINUTES_PENDING_INVITE);

				invites = client.getInvites();
				ListIterator<Invite> li = invites.listIterator();
				synchronized (invites) {
					// clearing expired invites
					nTotal = invites.size();
					while (li.hasNext())
						if (li.next().time().isBefore(expired))
							li.remove();

					System.out.println(
							"There are " + invites.size() + " invites (" + (nTotal - invites.size()) + " expired)");
					for (Invite inv : invites) {
						System.out.println("#" + invite++ + " [" + inv.time() + "] from " + inv.from());
					}
					nTotal = invites.size();
				}
				System.out.println("[" + ACTION_BACK + "]: back"
						+ (nTotal == 0 ? "" : "\t[" + ACTION_ACCEPT_INVITE + "] accept an invite"));
				try {
					result = Integer.parseInt(in.readLine());
				} catch (NumberFormatException | IOException e) {
				}
				hasChosen = options.contains(result);
			} while (!hasChosen && nTotal > 0);
			// to prevent the user from choosing this option between
			// an empty invites[] and when an invite gets delivered
			if (nTotal == 0)
				result = ACTION_BACK;
			switch (result) {
			case ACTION_BACK:
				return NO_INVITE;
			case ACTION_ACCEPT_INVITE:
				invite = -1;

				do {
					System.out.println("Choose one");
					try {
						invite = Integer.parseInt(in.readLine());
					} catch (NumberFormatException | IOException e) {
					}
				} while (invite < 0 || invite >= nTotal);
				return invite;
			default:
				return NO_INVITE;
			}
		
	}

	private void logout() throws RemoteException {
		if (server != null) {
			if (server.logout(client))
				System.out.println("Logged out successfully");
			else
				System.err.println("Error while logging out" + System.lineSeparator());
		} else {
			System.err.println("Cannot connect to the server [null]" + System.lineSeparator());
		}
		client = null;
	}

	private boolean isGameOpen(LocalDateTime playerStartTime, LocalDateTime gameStartTime) {
		LocalDateTime now;
		return !playerStartTime.isBefore((now = LocalDateTime.now()).minusMinutes(Client.TIME_TYPING_WORDS_MIN))
				&& !gameStartTime.isBefore(now.minusMinutes(Client.TIME_OPEN_GAME_MIN));
	}

	private static void init() {
		TIME_OPEN_GAME_MIN = Integer.parseInt(Utils.FindInFile(configFile, "TIME_OPEN_GAME_MIN"));
		MIN_PASSWORD_LENGTH = Integer.parseInt(Utils.FindInFile(configFile, "MIN_PASSWORD_LENGTH"));
		MIN_PLAYERS_PER_MATCH = Integer.parseInt(Utils.FindInFile(configFile, "MIN_PLAYERS_PER_MATCH"));
		TIME_TYPING_WORDS_MIN = Integer.parseInt(Utils.FindInFile(configFile, "TIME_TYPING_WORDS_MIN"));
		MAX_MINUTES_PENDING_INVITE = Long.parseLong(Utils.FindInFile(configFile, "MAX_MINUTES_PENDING_INVITE"));
		ID_PREFIX = Utils.FindInFile(configFile, "ID_PREFIX");
		SERVER_NAME = Utils.FindInFile(configFile, "SERVER_NAME");
		TCP_PORT = Integer.parseInt(Utils.FindInFile(configFile, "TCP_PORT"));
		UDP_PORT = Integer.parseInt(Utils.FindInFile(configFile, "UDP_PORT"));
		SERVER_MULTICAST = Utils.FindInFile(configFile, "SERVER_MULTICAST");
		SERVER_MULTICAST_PORT = Integer.parseInt(Utils.FindInFile(configFile, "SERVER_MULTICAST_PORT"));
		MAX_PACKET_SIZE = Integer.parseInt(Utils.FindInFile(configFile, "MAX_PACKET_SIZE"));
		NOTIFY_INVITE = Boolean.parseBoolean(Utils.FindInFile(configFile, "NOTIFY_INVITE"));

		assert (MAX_PACKET_SIZE > 0 && isPortRangeValid(SERVER_MULTICAST_PORT) && isPortRangeValid(UDP_PORT)
				&& isPortRangeValid(TCP_PORT) && SERVER_MULTICAST.length() > 0 && SERVER_NAME.length() > 0
				&& ID_PREFIX.length() > 0 && MAX_MINUTES_PENDING_INVITE > 0 && TIME_TYPING_WORDS_MIN > 0
				&& MIN_PLAYERS_PER_MATCH > 1 && MIN_PASSWORD_LENGTH > 0 && TIME_OPEN_GAME_MIN > 0);
	}

	private static boolean isPortRangeValid(int port) {
		return port > 0 && port <= 65535;
	}

	private class ClientScoresListener implements Runnable {
		private static final int DELAY_MIN = 3;
		private final int TIMEOUT = 1000 * 60 * (DELAY_MIN + TIME_OPEN_GAME_MIN);

		LocalDateTime start;
		String id;
		int nPlayers;

		ClientScoresListener(int invite_id, int nPlayers) {
			this.start = LocalDateTime.now();
			this.nPlayers = nPlayers;
			// avoiding IDs such as 11 to collide with 1 (str.startsWith(..))
			this.id = "" + invite_id + " ";
		}

		@Override
		public void run() {
			Thread.currentThread().setName("ClientSL " + id);
			try (MulticastSocket sk = new MulticastSocket(SERVER_MULTICAST_PORT);) {
				InetAddress mcGroup = InetAddress.getByName(SERVER_MULTICAST);
				sk.joinGroup(mcGroup);
				DatagramPacket packet = null;
				String data;
				sk.setSoTimeout(TIMEOUT);

				// spins
				while (nPlayers > 0
						&& !LocalDateTime.now().minusMinutes(DELAY_MIN + TIME_OPEN_GAME_MIN).isAfter(start)) {
					try {
						sk.receive(packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE));
					} catch (SocketTimeoutException e) {
						continue;
					}
					data = new String(packet.getData(), StandardCharsets.UTF_8);
					if (data.startsWith(id)) {
						System.out.println("Game #" + id + "\t" + data.substring(id.length()));
						--nPlayers;
					}
					packet.setLength(MAX_PACKET_SIZE);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}
}
