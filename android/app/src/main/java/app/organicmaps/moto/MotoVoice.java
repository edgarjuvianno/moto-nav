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
                    "coba", "dong", "ya", "yuk", "deh", "nih", "please", "minta", "bisa", "bisakah", "kita", "kami");

  private static final List<String> SEARCH_PREFIXES =
      Arrays.asList("navigasi ke ", "arahkan ke ", "rute ke ", "antar ke ", "pergi ke ", "cari ", "carikan ", "temukan ");

  private static final List<String> OPEN_PREFIXES = Arrays.asList("buka ", "jalankan ", "bukain ", "open ");

  private static final List<String> PLACES =
      Arrays.asList("pom bensin", "spbu", "bensin", "bengkel", "tambal ban", "atm", "masjid", "musholla", "mushola",
                    "toilet", "rumah makan", "restoran", "warung", "kafe", "cafe", "indomaret", "alfamart",
                    "minimarket", "apotek", "rumah sakit", "klinik", "hotel", "parkir", "kantor polisi", "stasiun",
                    "terminal", "bandara");

  private static final Pattern TRAVEL = Pattern.compile(
      "^(?:pergi |jalan |berangkat |antar |antarkan |anterin |balik )?(?:ke arah|ke|menuju|tujuan)\\s+(.+)$");

  private static final String[][] PHRASES = {
      {"lagu berikutnya", "NEXT"},     {"lagu selanjutnya", "NEXT"},   {"berikutnya", "NEXT"},
      {"next", "NEXT"},                {"lagu sebelumnya", "PREVIOUS"}, {"sebelumnya", "PREVIOUS"},
      {"jeda", "PAUSE"},               {"jeda musik", "PAUSE"},         {"pause", "PAUSE"},
      {"stop musik", "PAUSE"},         {"putar musik", "PLAY"},         {"putar lagu", "PLAY"},
      {"lanjutkan musik", "PLAY"},     {"play", "PLAY"},                {"daftar aplikasi", "DRAWER"},
      {"menu aplikasi", "DRAWER"}};

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
      String t = stripFillers(normalize(raw));
      if (t.isEmpty())
        continue;
      String rest = fuzzyPrefix(t, Arrays.asList("navigasi ke", "arahkan ke", "rute ke", "cari", "carikan"));
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
      String rest = fuzzyPrefix(stripFillers(normalize(raw)), Arrays.asList("buka", "jalankan"));
      if (rest != null && !rest.isEmpty())
        return new Command(raw, Kind.OPEN_APP, rest);
    }
    return null;
  }

  @Nullable
  private static Command parseExact(@NonNull String raw)
  {
    String t = stripFillers(normalize(raw));
    if (t.isEmpty())
      return null;
    Matcher m = TRAVEL.matcher(t);
    if (m.find())
      return new Command(raw, Kind.SEARCH, m.group(1).trim());
    for (String prefix : SEARCH_PREFIXES)
    {
      int i = t.indexOf(prefix);
      if (i == 0 || (i > 0 && t.charAt(i - 1) == ' '))
      {
        String q = t.substring(i + prefix.length()).trim();
        if (!q.isEmpty())
          return new Command(raw, Kind.SEARCH, q);
      }
    }
    if (has(t, "berikut", "selanjutnya", "next", "skip", "ganti lagu"))
      return new Command(raw, Kind.NEXT, "");
    if (has(t, "sebelum", "previous", "kembali lagu", "lagu tadi"))
      return new Command(raw, Kind.PREVIOUS, "");
    if (has(t, "jeda", "pause", "berhenti", "stop", "matikan musik"))
      return new Command(raw, Kind.PAUSE, "");
    if (has(t, "putar", "play", "lanjutkan", "nyalakan musik"))
      return new Command(raw, Kind.PLAY, "");
    if (t.contains("daftar aplikasi") || t.equals("aplikasi") || t.equals("menu"))
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
      if (t.contains(place))
        return new Command(raw, Kind.SEARCH, t.replaceFirst("^(di mana|dimana|ada) ", "").trim());
    }
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
