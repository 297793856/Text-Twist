import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/*
 * conditionally thread safe
 */
// 1:1 with an invite (id matching)
public class Match implements Serializable, Comparable<Match> {
	private static final long serialVersionUID = 1L;

	private static final String DEFAULT_LETTERS = "rtnesowk";

	// { id, match }
	private static Map<Integer, Match> matches = new TreeMap<>();

	private int _id;
	private Invite invite;
	private String letters = ""; // can't synchronize on null
	// { player, score }
	private Map<String, Integer> scores;
	private List<String> playersNames;

	private Match(Invite invite) {
		this._id = invite.id();
		Match.matches.put(_id, this);
		this.scores = new TreeMap<>();
		this.invite = invite;
		this.playersNames = new ArrayList<>();
		for (IClient c : invite.to())
			try {
				this.playersNames.add(c.getName());
			} catch (Exception e) {
			}
	}

	/**
	 * 
	 * @param i invite
	 * @return the corresponding match to the given invite
	 */
	public static Match getMatch(Invite i) {
		Match result = null;
		synchronized (matches) {
			result = matches.get(i.id());
			if (result == null)
				matches.put(i.id(), result = new Match(i));
		}
		return result;
	}

	/**
	 * 
	 * @return a String with all and only the letters that can be used by any client for this match
	 */
	public String getLetters() {
		synchronized (this.letters) {
			return letters;
		}
	}

	/**
	 * 
	 * @param s the String to be returned by getLetters()
	 */
	public void setLetters(String s) {
		boolean hasData = s != null && !s.equals("");
		synchronized (this.letters) {
			if (this.letters.equals("") && hasData)
				this.letters = Utils.RandomizeString(s);
			else if (!hasData)
				this.letters = DEFAULT_LETTERS;
		}
	}

	/**
	 * 
	 * @return a unique id for this match
	 */
	public int id() {
		return _id;
	}

	/**
	 * 
	 * @return the invite this match is bound to
	 */
	public Invite invite() {
		return invite;
	}

	/**
	 * 
	 * @return a Map<> where: for each <k, v>: k is the username of a player of this match and v is k's final score for this match
	 */
	public Map<String, Integer> getScores() {
		return scores;
	}

	/**
	 * 
	 * @param name a username
	 * @return name's score for this match; 0 for any given name for which either name's not playing in this match or has a score of 0
	 */
	public int getScore(String name) {
		int r = 0;
		synchronized (scores) {
			if (scores.containsKey(name))
				r = scores.get(name);
		}
		return r;
	}

	/**
	 * 
	 * @param name username
	 * @param v score
	 */
	public void setScore(String name, int v) {
		synchronized (scores) {
			scores.put(name, v);
		}
	}

	/**
	 * 
	 * @param name username
	 * @param v sets name's score to getScore(name) + v
	 */
	public void addScore(String name, int v) {
		synchronized (scores) {
			scores.put(name, v + scores.get(name));
		}
	}

	@Override
	public int compareTo(Match m) {
		return this.id() - m.id();
	}

	/**
	 * 
	 * @return the number of players in this match
	 */
	public int players() {
		return this.invite().to().size();
	}

	/**
	 * 
	 * @return a List<> of Strings containing all and only the names of the players
	 */
	public List<String> playersNames() {
		return playersNames;
	}

	/**
	 * 
	 * @param m match that's now over
	 */
	public static void end(Match m) {
		synchronized (matches) {
			matches.remove(m.id());
		}
	}

	//
	// no equals: each instance is unique (so Object.equals() is fine)
	//
}
