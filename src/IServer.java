import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IServer extends Remote {
	public final static String REMOTE_OBJECT_NAME = "SERVER";

	/**
	 * 
	 * @param name client's name
	 * @param pw client's password
	 * @return one of the codes in Utils.SIGNUP_ERRS:
	 * 			E_OK: the action succeeded
	 * 			E_NAME: the value of the param name is not valid
	 * 			E_NAME_TAKEN: there exists another user with the same name
	 * 			E_PW_NONE: no password provided
	 * 			E_PW_LEN: param pw has a length that's lower than the minimum allowed
	 * @throws RemoteException
	 */
	public int signup(String name, String pw) throws RemoteException;

	/**
	 * 
	 * @param name username
	 * @param pw password
	 * @param client exported object representing the client
	 * @return true iff there exists (ie. has used signup()) a user with the given params
	 * @throws RemoteException
	 */
	public boolean login(String name, String pw, IClient client) throws RemoteException;

	/**
	 * 
	 * @param c exported client's object
	 * @return true iff the operation has succeded (ie. the user had used login() successfully, 
	 * 			without disconnecting or using logout() prior to now)
	 * @throws RemoteException
	 */
	public boolean logout(IClient c) throws RemoteException;

}
