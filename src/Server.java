import java.io.File;
import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;

public class Server {
	public static final int STR_MAX_LEN = 0x100;

	protected static int MAX_PACKET_SIZE = 0x200;
	protected static String SCORES_DB_PATH;
	protected static String configFile = "CONFIG.txt";
	protected static int TCP_PORT;
	protected static int UDP_PORT;
	protected static int TIME_OPEN_GAME_MIN;
	protected static int MIN_PASSWORD_LENGTH;
	protected static int MIN_PLAYERS_PER_MATCH;
	protected static int TIME_TYPING_WORDS_MIN;
	protected static long MAX_MINUTES_PENDING_INVITE;
	protected static String ID_PREFIX;
	protected static String SERVER_NAME;
	protected static String DICTIONARY_PATH;
	protected static String SALT;
	protected static int MIN_LETTERS;
	protected static String SERVER_MULTICAST;
	protected static int SERVER_MULTICAST_PORT;

	public static void main(String[] args) {
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

		(new Server()).exec();
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
		DICTIONARY_PATH = Utils.FindInFile(configFile, "DICTIONARY_PATH");
		SCORES_DB_PATH = Utils.FindInFile(configFile, "SCORES_DB_PATH");
		SALT = Utils.FindInFile(configFile, "SERVER_SALT");
		MIN_LETTERS = Integer.parseInt(Utils.FindInFile(configFile, "MIN_LETTERS"));
		SERVER_MULTICAST = Utils.FindInFile(configFile, "SERVER_MULTICAST");
		SERVER_MULTICAST_PORT = Integer.parseInt(Utils.FindInFile(configFile, "SERVER_MULTICAST_PORT"));
		MAX_PACKET_SIZE = Integer.parseInt(Utils.FindInFile(configFile, "MAX_PACKET_SIZE"));

		assert (MAX_PACKET_SIZE > 0 && isPortRangeValid(SERVER_MULTICAST_PORT) && MIN_LETTERS > 0
				&& isPortRangeValid(UDP_PORT) && isPortRangeValid(TCP_PORT) && SERVER_MULTICAST.length() > 0
				&& SCORES_DB_PATH.length() > 0 && DICTIONARY_PATH.length() > 0 && SERVER_NAME.length() > 0
				&& ID_PREFIX.length() > 0 && MAX_MINUTES_PENDING_INVITE > 0 && TIME_TYPING_WORDS_MIN > 0
				&& MIN_PLAYERS_PER_MATCH > 1 && MIN_PASSWORD_LENGTH > 0 && TIME_OPEN_GAME_MIN > 0);
	}

	private void exec() {
		IServer serverStub = null;
		IServer server = (IServer) ServerRemote.getServer(configFile);

		try {
			serverStub = (IServer) UnicastRemoteObject.exportObject(server, 0);
			LocateRegistry.getRegistry().bind(IServer.REMOTE_OBJECT_NAME, serverStub);
			System.out.println("Server running");
		} catch (AlreadyBoundException e) {
			try {
				LocateRegistry.getRegistry().rebind(IServer.REMOTE_OBJECT_NAME, serverStub);
				System.out.println("Server (re)bound");
			} catch (RemoteException e1) {
				e1.printStackTrace();
			}
		} catch (Exception e1) {
			System.err.println("The server CANNOT start");
			e1.printStackTrace();
			System.out.flush();
			System.exit(0);
		}

	}

	private static boolean isPortRangeValid(int port) {
		return port > 0 && port <= 65535;
	}
}
