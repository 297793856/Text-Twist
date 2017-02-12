import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface IClient extends Remote {
	public static final String REMOTE_OBJ_NAME = "CLIENT";

	/**
	 * 
	 * @return the client's username
	 * @throws RemoteException
	 */
	public String getName() throws RemoteException;

	/**
	 * 
	 * @return the client's id
	 * @throws RemoteException
	 */
	public long getId() throws RemoteException;

	/**
	 * 
	 * @param v new value for the id
	 * @throws RemoteException
	 */
	public void setId(long v) throws RemoteException;

	/**
	 * 
	 * @param invite invite sent to the client
	 * @throws RemoteException
	 */
	public void addInvite(Invite invite) throws RemoteException;

	/**
	 * 
	 * @return the list of invites added via addInvite()
	 * @throws RemoteException
	 */
	public List<Invite> getInvites() throws RemoteException;

	/**
	 * 
	 * @param c cookie
	 * @throws RemoteException
	 */
	public void setCookie(Cookie c) throws RemoteException;

	/**
	 * 
	 * @return the last cookie set via setCookie()
	 * @throws RemoteException
	 */
	public Cookie getCookie() throws RemoteException;

	/**
	 * 
	 * @return the client's password
	 * @throws RemoteException
	 */
	public String getPassw() throws RemoteException;

	/**
	 * 
	 * @param o Object to use for comparison
	 * @return true iff o and this represent the same object
	 * @throws RemoteException
	 */
	public boolean eq(Object o) throws RemoteException;

}
