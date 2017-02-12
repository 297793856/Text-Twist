import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

/*
 * unconditionally thread safe
 * except for: IsInFile when getlock is false
 */
public class Utils {
	// string format: key = value
	static final String SEPARATOR = " = ";

	private static ConcurrentMap<String, Lock> fileLocks = new ConcurrentHashMap<>();

	/**
	 * 
	 * @param fp path to the file
	 * @param pattern pattern to be found (key)
	 * @return the corresponding value to the given pattern (key)
	 */
	static public String FindInFile(String fp, String pattern) {
		String result = "", line;
		RandomAccessFile f = null;
		if (fp == null || pattern == null || pattern.equals(""))
			return result;
		try {
			f = new RandomAccessFile(fp, "r");
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
			return null;
		}

		Lock lock = new ReentrantLock(true), old = null;
		old = fileLocks.putIfAbsent(fp, lock);
		if (old != null)
			lock = old;

		try {
			lock.lockInterruptibly();
			f.seek(0);
			line = f.readLine();
			while (line != null && result.equals("")) {
				if (line.indexOf(pattern) == 0 && line.charAt(pattern.length()) == SEPARATOR.charAt(0))
					result = line.substring(pattern.length() + SEPARATOR.length(), line.length());
				line = f.readLine();
			}
		} catch (IOException e) {
			return result;
		} catch (InterruptedException e) {
			return result;
		} finally {
			try {
				f.close();
			} catch (Exception e) {
			}
			lock.unlock();
		}
		return result;
	}

	/**
	 * 
	 * @param fp file's path
	 * @param s string to find 
	 * @param getlock if there's a need to lock the access on the given file
	 * @return true iff the file which path is fp contains s
	 */
	public static boolean IsInFile(String fp, String s, boolean getlock) {
		RandomAccessFile f = null;
		String line;
		boolean found = false;

		Lock lock = new ReentrantLock(true), old = null;
		old = fileLocks.putIfAbsent(fp, lock);
		if (old != null)
			lock = old;

		if (getlock)
			old = fileLocks.putIfAbsent(fp, lock);
		if (getlock && old != null)
			lock = old;

		try {
			if (getlock)
				lock.lockInterruptibly();
			f = new RandomAccessFile(fp, "r");
			if (f == null || s == null || s.equals(""))
				return false;
			f.seek(0);
			line = f.readLine();
			while (line != null && !found) {
				if (line.equals(s))
					found = true;
				line = f.readLine();
			}
		} catch (IOException | InterruptedException e) {
			return found;
		} finally {
			if (f != null)
				try {
					f.close();
				} catch (IOException e) {
				}
			if (getlock)
				lock.unlock();
		}
		return found;
	}

	/**
	 * 
	 * @param fp file's path
	 * @param out <key, value>
	 * @return true iff the method succeeded
	 */
	static public boolean WriteToFile(String fp, Pair<String, String> out) {
		if (fp == null || out == null || out.v1 == null || out.v2 == null)
			return false;
		RandomAccessFile f;
		try {
			f = new RandomAccessFile(fp, "rw");
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
			return false;
		}
		Lock lock = new ReentrantLock(true), old = null;
		old = fileLocks.putIfAbsent(fp, lock);
		if (old != null)
			lock = old;
		try {
			lock.lockInterruptibly();
			f.seek(f.length());
			String o = out.v1 + SEPARATOR + out.v2 + System.lineSeparator();
			f.write(o.getBytes());
		} catch (IOException | InterruptedException e) {
			return false;
		} finally {
			try {
				f.close();
			} catch (IOException e) {
			}
			lock.unlock();
		}
		return true;
	}

	/**
	 * 
	 * @param fpath file's path
	 * @param user key
	 * @param oldval value to be found in the file, given the given key
	 * @param newval value to be written in place of oldval
	 * @return true iff each occurrence of <key, oldval> is now <key, newval>
	 */
	static public boolean UpdateInFile(String fpath, String user, String oldval, String newval) {
		// out = <user, newvalue>
		if (fpath == null || user == null || oldval == null || newval == null)
			return false;

		Path path = Paths.get(fpath);
		Lock lock = new ReentrantLock(true), old = null;
		old = fileLocks.putIfAbsent(fpath, lock);
		if (old != null)
			lock = old;
		String file;
		try {
			lock.lockInterruptibly();
			file = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			file = file.replaceAll(user + SEPARATOR + oldval, user + SEPARATOR + newval);
			Files.write(path, file.getBytes(StandardCharsets.UTF_8));
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			return false;
		} finally {
			lock.unlock();
		}

		return true;
	}

	/**
	 * 
	 * @param input string to be shuffled
	 * @return a string with all and only the chars in input in any order
	 */
	static public String RandomizeString(String input) {
		List<String> ls = Arrays.asList(input.split(""));
		Collections.shuffle(ls);
		return String.join("", ls);
	}

	/**
	 * 
	 * @param fp file's path
	 * @param line number of line in the file
	 * @return the string found in the file at line line or the last line that could be read
	 */
	static public String AtLine(String fp, long line) {
		FileReader fr = null;
		BufferedReader br = null;
		String result = "";
		long max = Utils.FileLines(fp);
		Lock lock = new ReentrantLock(true), old = null;
		old = fileLocks.putIfAbsent(fp, lock);
		if (old != null)
			lock = old;
		try {
			lock.lockInterruptibly();
			String lineStr;
			fr = new FileReader(new File(fp));
			br = new BufferedReader(fr);

			lineStr = result = br.readLine();
			while (lineStr != null && --line > 0 && line <= max) {
				lineStr = br.readLine();
				if (lineStr != null)
					result = lineStr;
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			try {
				if (fr != null)
					fr.close();
				if (br != null)
					br.close();
			} catch (Exception e) {
			}
			lock.unlock();
		}
		return result;
	}

	/**
	 * 
	 * @param fp file's path
	 * @return the number of lines of the given file
	 */
	static public long FileLines(String fp) {
		long lines = 0;
		FileReader fr = null;
		BufferedReader br = null;
		Lock lock = new ReentrantLock(true), old = null;
		old = fileLocks.putIfAbsent(fp, lock);
		if (old != null)
			lock = old;
		try {
			lock.lockInterruptibly();
			fr = new FileReader(new File(fp));
			br = new BufferedReader(fr);
			while (br.readLine() != null)
				++lines;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			try {
				if (fr != null)
					fr.close();
				if (br != null)
					br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			lock.unlock();
		}
		return lines + 1;
	}

	/**
	 * 
	 * @param fp file's path
	 * @return a Map of all the <key, value>s that could be found
	 */
	static public Map<String, String> ParseFile(String fp) {
		Lock lock = new ReentrantLock(true), old = null;
		old = fileLocks.putIfAbsent(fp, lock);
		if (old != null)
			lock = old;
		Map<String, String> result = new TreeMap<>();
		boolean locked = false;
		try (FileReader fr = new FileReader(new File(fp)); BufferedReader br = new BufferedReader(fr);) {
			lock.lockInterruptibly();
			locked = true;
			String line = "";
			String[] out;
			while (line != null) {
				line = br.readLine();
				if (line != null) {
					out = line.split(SEPARATOR);
					if (out != null)
						result.put(out[0], out[1]);
				}
			}
		} catch (IOException | InterruptedException e) {

		} finally {
			if (locked)
				lock.unlock();
		}

		return result;
	}

	/**
	 * 
	 * @param o Object
	 * @return the representation of o in bytes
	 */
	static public byte[] ToByteArray(Object o) {
		if (o == null)
			return null;
		ByteArrayOutputStream bs = new ByteArrayOutputStream();
		try {
			ObjectOutputStream os = new ObjectOutputStream(bs);
			os.writeObject(o);
			os.close();
			return bs.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

	}

	/**
	 * 
	 * @param arr an array of bytes
	 * @return the Object representation of arr
	 */
	static public Object ByteArrayToObject(byte[] arr) {
		if (arr == null)
			return null;

		try (ByteArrayInputStream bs = new ByteArrayInputStream(arr);
				ObjectInputStream i = new ObjectInputStream(bs);) {
			return i.readObject();
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 
	 * @param in String
	 * @return a String with the same chars found in in, in the same order, but with no duplicates
	 */
	public static String StringNoDuplicates(String in) {
		Set<Character> set = new TreeSet<>();
		StringBuilder s = new StringBuilder();
		for (Character c : in.toCharArray()) {
			if (!set.contains(c)) {
				set.add(c);
				s.append(c);
			}
		}
		return s.toString();
	}

}

class SIGNUP_ERRS {
	public final static int E_OK = 0;
	public final static int E_NAME = 1;
	public final static int E_NAME_TAKEN = 2;
	public final static int E_PW_NONE = 3;
	public final static int E_PW_LEN = 4;

	/**
	 * 
	 * @param v a number corresponding to one of the constants defined in SIGNUP_ERRS
	 * @return the String representation of v; "NONE" if v hasn't been found among the available codes
	 */
	public static String toStr(int v) {
		switch (v) {
		case E_OK:
			return "E_OK";
		case E_NAME:
			return "E_NAME";
		case E_NAME_TAKEN:
			return "E_NAME_TAKEN";
		case E_PW_NONE:
			return "E_PW_NONE";
		case E_PW_LEN:
			return "E_PW_LEN";
		default:
			return "NONE";
		}
	}
}

/*
 * not thread safe
 */
class Pair<T, U> implements Serializable {
	private static final long serialVersionUID = 1L;
	public final T v1;
	public final U v2;

	public Pair(T v1, U v2) {
		this.v1 = v1;
		this.v2 = v2;
	}

	/**
	 * 
	 * @param p an instance of Pair<>
	 * @return the bytes representing p
	 */
	static public byte[] ToByteArray(Pair<?, ?> p) {
		if (p == null)
			return null;

		try (ByteArrayOutputStream bs = new ByteArrayOutputStream();
				ObjectOutputStream os = new ObjectOutputStream(bs);) {
			os.writeObject(p);
			return bs.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

	}

	/**
	 * 
	 * @param arr array of bytes
	 * @return the Pair<> instance representing arr
	 */
	static public Pair<?, ?> FromByteArray(byte[] arr) {
		if (arr == null)
			return null;
		try (ByteArrayInputStream bs = new ByteArrayInputStream(arr);
				ObjectInputStream i = new ObjectInputStream(bs);) {
			return (Pair<?, ?>) i.readObject();
		} catch (IOException | ClassNotFoundException e) {
			return null;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Pair))
			return false;
		Pair<?, ?> p = (Pair<?, ?>) o;
		return this.v1 == null ? false : (this.v2 == null ? false : (this.v1.equals(p.v1) && this.v2.equals(p.v2)));
	}
}

/*
 * not thread safe
 */
class Triple<T, U, V> implements Serializable {
	private static final long serialVersionUID = 1L;
	public final T v1;
	public final U v2;
	public final V v3;

	public Triple(T v1, U v2, V v3) {
		this.v1 = v1;
		this.v2 = v2;
		this.v3 = v3;
	}
	
	/**
	 * 
	 * @param t an instance of Triple<>
	 * @return the bytes representing t
	 */
	static public byte[] ToByteArray(Triple<?, ?, ?> t) {
		if (t == null)
			return null;

		try (ByteArrayOutputStream bs = new ByteArrayOutputStream();
				ObjectOutputStream os = new ObjectOutputStream(bs);) {
			os.writeObject(t);
			return bs.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

	}
	
	/**
	 * 
	 * @param arr array of bytes
	 * @return the Triple<> instance representing arr
	 */
	static public Triple<?, ?, ?> FromByteArray(byte[] arr) {
		if (arr == null)
			return null;

		try (ByteArrayInputStream bs = new ByteArrayInputStream(arr);
				ObjectInputStream i = new ObjectInputStream(bs);) {

			return (Triple<?, ?, ?>) i.readObject();
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Triple))
			return false;
		Triple<?, ?, ?> p = (Triple<?, ?, ?>) o;
		return this.v1 == null ? false
				: (this.v2 == null ? false
						: (this.v3 == null ? false
								: this.v1.equals(p.v1) && this.v2.equals(p.v2) && this.v3.equals(p.v3)));
	}

}