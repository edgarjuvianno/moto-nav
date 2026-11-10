package app.organicmaps.moto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MotoVoice
{
  public enum Kind
  {
    SEARCH,
    OPEN_APP,
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    DRAWER
  }

  public static final class Command
  {
    public final String heard;
    public final Kind kind;
    public final String argument;

    Command(String heard, Kind kind, String argument)
    {
      this.heard = heard;
      this.kind = kind;
      this.argument = argument;
    }
  }

  private static final List<String> FILLERS =
      Arrays.asList("saya", "aku", "gue", "gw", "mau", "mahu", "nak", "ingin", "pengen", "pingin", "pengin", "tolong",
                    "coba", "dong", "ya", "yuk", "deh", "nih", "please", "minta", "bisa", "bisakah", "kita", "kami",
                    "hey", "hi", "ok", "okay", "i", "want", "wanna", "need", "would", "like", "can", "could", "you",
                    "lets", "let", "s", "to", "the");

  private static final List<String> SEARCH_PREFIXES =
      Arrays.asList("navigasi ke ", "arahkan ke ", "rute ke ", "antar ke ", "pergi ke ", "cari ", "carikan ", "temukan ",
                    "navigate to ", "take me to ", "directions to ", "drive to ", "ride to ", "go to ", "search for ",
                    "search ", "find me ", "find ", "where is ");

  private static final List<String> OPEN_PREFIXES = Arrays.asList("buka ", "jalankan ", "bukain ", "open ", "launch ", "start ");

  private static final List<String> PLACES =
      Arrays.asList("pom bensin", "spbu", "bensin", "bengkel", "tambal ban", "atm", "masjid", "musholla", "mushola",
                    "toilet", "rumah makan", "restoran", "warung", "kafe", "cafe", "indomaret", "alfamart",
                    "minimarket", "apotek", "rumah sakit", "klinik", "hotel", "parkir", "kantor polisi", "stasiun",
                    "terminal", "bandara", "gas station", "petrol station", "fuel", "mosque", "restaurant", "coffee",
                    "hospital", "pharmacy", "parking", "police", "airport", "train station", "workshop", "mechanic");

  private static final Pattern TRAVEL = Pattern.compile(
      "^(?:pergi |jalan |berangkat |antar |antarkan |anterin |balik )?(?:ke arah|ke|menuju|tujuan)\\s+(.+)$");

  private static final String[][] PHRASES = {
      {"lagu berikutnya", "NEXT"},     {"lagu selanjutnya", "NEXT"},   {"berikutnya", "NEXT"},
      {"next", "NEXT"},                {"lagu sebelumnya", "PREVIOUS"}, {"sebelumnya", "PREVIOUS"},
      {"jeda", "PAUSE"},               {"jeda musik", "PAUSE"},         {"pause", "PAUSE"},
      {"stop musik", "PAUSE"},         {"putar musik", "PLAY"},         {"putar lagu", "PLAY"},
      {"lanjutkan musik", "PLAY"},     {"play", "PLAY"},                {"daftar aplikasi", "DRAWER"},
      {"menu aplikasi", "DRAWER"},     {"next song", "NEXT"},           {"skip song", "NEXT"},
      {"previous song", "PREVIOUS"},   {"last song", "PREVIOUS"},       {"pause music", "PAUSE"},
      {"stop music", "PAUSE"},         {"play music", "PLAY"},          {"resume music", "PLAY"},
      {"open apps", "DRAWER"},         {"app drawer", "DRAWER"}};

  private MotoVoice() {}

  @NonNull
  public static String normalize(@NonNull String text)
  {
    return Normalizer.normalize(text, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9 ]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  @NonNull
  private static String stripFillers(@NonNull String text)
  {
    String[] words = text.split(" ");
    StringBuilder sb = new StringBuilder();
    boolean leading = true;
    for (String w : words)
    {
      if (leading && FILLERS.contains(w))
        continue;
      leading = false;
      if (w.equals("dong") || w.equals("ya") || w.equals("please"))
        continue;
      if (sb.length() > 0)
        sb.append(' ');
      sb.append(w);
    }
    return sb.toString();
  }

  @NonNull
  public static String cleanQuery(@NonNull String q)
  {
    q = q.replaceAll("([a-z])\\1+", "$1");
    String r = (" " + q + " ").replaceAll(" (terdekat|paling dekat|nearest|closest|near me|nearby|the|a|an) ", " ");
    r = r.replaceAll(" (terdekat|paling dekat|nearest|closest|near me|nearby|the|a|an) ", " ").trim();
    return r.isEmpty() ? q : r;
  }

  public static boolean isFuel(@NonNull String q)
  {
    String n = cleanQuery(normalize(q));
    return n.equals("spbu") || n.equals("pom bensin") || n.equals("bensin") || n.equals("gas station")
        || n.equals("petrol station") || n.equals("fuel") || n.equals("gas") || n.equals("pertamina");
  }

  @Nullable
  public static Command parse(@NonNull List<String> candidates)
  {
    for (String raw : candidates)
    {
      Command c = parseExact(raw);
      if (c != null)
        return c;
    }
    Command best = null;
    double bestScore = 0;
    for (String raw : candidates)
    {
      String t = repair(stripFillers(normalize(raw)));
      if (t.isEmpty())
        continue;
      String rest = fuzzyPrefix(t, Arrays.asList("navigasi ke", "arahkan ke", "rute ke", "cari", "carikan", "navigate to",
                                                     "take me to", "find", "search for"));
      if (rest != null && !rest.isEmpty())
        return new Command(raw, Kind.SEARCH, rest);
      for (String[] p : PHRASES)
      {
        double score = similarity(t, p[0]);
        if (score > bestScore)
        {
          bestScore = score;
          best = new Command(raw, Kind.valueOf(p[1]), "");
        }
      }
    }
    if (best != null && bestScore >= 0.62)
      return best;
    for (String raw : candidates)
    {
      String rest = fuzzyPrefix(stripFillers(normalize(raw)), Arrays.asList("buka", "jalankan", "open", "launch"));
      if (rest != null && !rest.isEmpty())
        return new Command(raw, Kind.OPEN_APP, rest);
    }
    return null;
  }

  private static final List<String> VERBS =
      Arrays.asList("cari", "carikan", "buka", "navigasi", "arahkan", "putar", "jeda", "find", "open", "navigate",
                    "play", "pause", "search");

  @NonNull
  static String dedupe(@NonNull String t)
  {
    String[] w = t.split(" ");
    for (int n = 1; n <= 4; n++)
    {
      if (w.length < n * 2)
        break;
      String first = String.join(" ", Arrays.copyOfRange(w, 0, n));
      boolean repeat = true;
      for (int i = n; i + n <= w.length; i += n)
        if (!String.join(" ", Arrays.copyOfRange(w, i, i + n)).equals(first))
        {
          repeat = false;
          break;
        }
      if (repeat && w.length % n == 0)
        return first;
    }
    return t;
  }

  @NonNull
  static String repair(@NonNull String t)
  {
    t = dedupe(t);
    t = (" " + t + " ").replace(" gastation ", " gas station ").replace(" guest station ", " gas station ")
            .replace(" gas stations ", " gas station ").trim();
    t = t.replaceFirst("^(4|for|four) ", "ke ");
    String[] ww = t.split(" ");
    if (ww.length >= 3 && !VERBS.contains(ww[0]))
    {
      String joined = ww[0] + ww[1];
      for (String v : VERBS)
        if (v.length() >= 6 && similarity(joined, v) >= 0.75)
        {
          t = v + " " + String.join(" ", Arrays.copyOfRange(ww, 2, ww.length));
          break;
        }
    }
    t = (" " + t + " ").replaceAll(" s p b u ", " spbu ").replaceAll(" s p b ", " spbu ").replaceAll(" spb ", " spbu ")
            .replaceAll(" s p bu ", " spbu ").replaceAll(" es pe be u ", " spbu ").trim();
    t = t.replaceAll("\\bgedeung\\b", "gedung");
    String[] w = t.split(" ");
    if (w.length >= 2 && w[0].length() >= 3 && !VERBS.contains(w[0]))
    {
      String best = null;
      double bestScore = 0;
      for (String v : VERBS)
      {
        double sc = similarity(w[0], v);
        if (sc > bestScore)
        {
          bestScore = sc;
          best = v;
        }
      }
      if (best != null && bestScore >= 0.6 && Math.abs(best.length() - w[0].length()) <= 1)
      {
        w[0] = best;
        t = String.join(" ", w);
      }
    }
    w = t.split(" ");
    if (w.length >= 2 && (w[0].equals("navigasi") || w[0].equals("arahkan")) && w[1].length() >= 5
        && (w[1].startsWith("ke") || w[1].startsWith("ku")) && !w[1].equals("kebun"))
    {
      w[1] = "ke " + w[1].substring(2);
      t = String.join(" ", w);
      w = t.split(" ");
    }
    if (w.length >= 2 && (w[0].equals("cari") || w[0].equals("carikan") || w[0].equals("find")
                          || w[0].equals("search")))
    {
      String rest = String.join("", Arrays.copyOfRange(w, 1, w.length));
      String place = null;
      double score = 0;
      for (String p : PLACES)
      {
        double sc = similarity(rest, p);
        if (sc > score)
        {
          score = sc;
          place = p;
        }
      }
      double need = rest.length() <= 5 ? 0.6 : 0.7;
      if (place != null && score >= need && !rest.equals(place.replace(" ", "")))
        t = w[0] + " " + place;
    }
    return t;
  }

  @Nullable
  private static Command parseExact(@NonNull String raw)
  {
    String t = repair(stripFillers(normalize(raw)));
    if (t.isEmpty())
      return null;
    if (t.matches("^(go|take me|drive|ride|navigate|head|pulang)( home| to home| to my home| ke rumah)?$")
        && !t.equals("go") && !t.equals("navigate") && !t.equals("drive") && !t.equals("ride") && !t.equals("head")
        || t.equals("home") || t.equals("pulang"))
      return new Command(raw, Kind.SEARCH, "home");
    Matcher m = TRAVEL.matcher(t);
    if (m.find())
      return new Command(raw, Kind.SEARCH, m.group(1).trim());
    for (String prefix : SEARCH_PREFIXES)
    {
      int i = t.indexOf(prefix);
      if (i == 0 || (i > 0 && t.charAt(i - 1) == ' '))
      {
        String q = t.substring(i + prefix.length()).trim().replaceFirst("^(the|a|an|nearest|closest) ", "").trim();
        if (!q.isEmpty())
          return new Command(raw, Kind.SEARCH, q);
      }
    }
    if (has(t, "berikut", "selanjutnya", "next", "skip", "ganti lagu"))
      return new Command(raw, Kind.NEXT, "");
    if (has(t, "sebelum", "previous", "kembali lagu", "lagu tadi", "last song", "go back"))
      return new Command(raw, Kind.PREVIOUS, "");
    if (has(t, "jeda", "pause", "berhenti", "stop", "matikan musik", "mute"))
      return new Command(raw, Kind.PAUSE, "");
    if (has(t, "putar", "play", "lanjutkan", "nyalakan musik", "resume"))
      return new Command(raw, Kind.PLAY, "");
    if (t.contains("daftar aplikasi") || t.equals("aplikasi") || t.equals("menu") || t.equals("apps")
        || t.contains("all apps") || t.contains("app drawer"))
      return new Command(raw, Kind.DRAWER, "");
    for (String prefix : OPEN_PREFIXES)
    {
      if (t.startsWith(prefix))
      {
        String q = t.substring(prefix.length()).replaceFirst("^aplikasi ", "").trim();
        if (!q.isEmpty())
          return new Command(raw, Kind.OPEN_APP, q);
      }
    }
    for (String place : PLACES)
    {
      int at = (" " + t + " ").indexOf(" " + place + " ");
      if (at >= 0)
        return new Command(raw, Kind.SEARCH, t.substring(at).trim());
    }
    int ke = t.lastIndexOf(" ke ");
    if (ke >= 0 && t.length() - ke > 6)
      return new Command(raw, Kind.SEARCH, t.substring(ke + 4).trim());
    int to = t.lastIndexOf(" to ");
    if (to >= 0 && t.length() - to > 6)
      return new Command(raw, Kind.SEARCH, t.substring(to + 4).replaceFirst("^(the|a|an) ", "").trim());
    return null;
  }

  private static boolean has(@NonNull String t, @NonNull String... words)
  {
    for (String w : words)
      if (t.contains(w))
        return true;
    return false;
  }

  @Nullable
  private static String fuzzyPrefix(@NonNull String t, @NonNull List<String> prefixes)
  {
    String[] words = t.split(" ");
    for (String prefix : prefixes)
    {
      int n = prefix.split(" ").length;
      if (words.length <= n)
        continue;
      String head = String.join(" ", Arrays.copyOfRange(words, 0, n));
      if (similarity(head, prefix) >= 0.7)
        return String.join(" ", Arrays.copyOfRange(words, n, words.length));
    }
    return null;
  }

  @Nullable
  public static MotoApps.Entry matchApp(@NonNull List<MotoApps.Entry> apps, @NonNull String query)
  {
    String q = normalize(query);
    for (MotoApps.Entry e : apps)
      if (normalize(e.label).equals(q))
        return e;
    for (MotoApps.Entry e : apps)
      if (normalize(e.label).startsWith(q))
        return e;
    for (MotoApps.Entry e : apps)
    {
      String n = normalize(e.label);
      if (n.contains(q) || q.contains(n))
        return e;
    }
    for (MotoApps.Entry e : apps)
    {
      String n = normalize(e.label);
      for (String w : q.split(" "))
        if (w.length() >= 3 && n.contains(w))
          return e;
    }
    return null;
  }

  private static double similarity(@NonNull String a, @NonNull String b)
  {
    String x = a.replace(" ", "");
    String y = b.replace(" ", "");
    if (x.isEmpty() || y.isEmpty())
      return 0;
    return 1.0 - (double) levenshtein(x, y) / Math.max(x.length(), y.length());
  }

  private static int levenshtein(@NonNull String a, @NonNull String b)
  {
    int[] prev = new int[b.length() + 1];
    int[] cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++)
      prev[j] = j;
    for (int i = 1; i <= a.length(); i++)
    {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++)
      {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return prev[b.length()];
  }
}
