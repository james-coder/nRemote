import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Headless test for the clickable faceplate (no calculator required). Verifies:
 *   1. every "KEY:" action is a real TI key name (from tests/valid-keys.txt),
 *      and every ARROW/PALETTE/toggle action is a recognised type;
 *   2. clicking the centre of each button resolves to that button (no gaps or
 *      wrong overlaps);
 *   3. no button has a degenerate (zero/negative-size) hit box.
 *
 * Reads FaceplatePanel's private button list by reflection so no test hook is
 * needed in production code. Exits non-zero on any failure (for CI).
 *
 * Usage: java FaceplateMapTest <repo-root>
 */
public class FaceplateMapTest {
    public static void main(String[] args) throws Exception {
        String repo = args.length > 0 ? args[0] : ".";

        Set<String> valid = new HashSet<String>();
        BufferedReader vr = new BufferedReader(new InputStreamReader(
                new FileInputStream(repo + "/tests/valid-keys.txt"), "UTF-8"));
        String line;
        while ((line = vr.readLine()) != null) { valid.add(line); }
        vr.close();

        Image img = ImageIO.read(new File(repo + "/src/faceplate.png"));
        Object panel = Class.forName("FaceplatePanel").getConstructor(Image.class).newInstance(img);
        Field bf = panel.getClass().getDeclaredField("buttons");
        bf.setAccessible(true);
        List<?> btns = (List<?>) bf.get(panel);

        int n = btns.size();
        String[] act = new String[n];
        int[][] box = new int[n][4];
        for (int i = 0; i < n; i++) {
            Object b = btns.get(i);
            act[i] = (String) field(b, "action");
            box[i][0] = intField(b, "x1"); box[i][1] = intField(b, "y1");
            box[i][2] = intField(b, "x2"); box[i][3] = intField(b, "y2");
        }

        List<String> errors = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            String e = validateAction(act[i], valid);
            if (e != null) errors.add("action [" + act[i] + "]: " + e);
            if (box[i][2] <= box[i][0] || box[i][3] <= box[i][1])
                errors.add("degenerate hit box for [" + act[i] + "]");
        }
        // centre of each button must resolve to that button (first-match, like at())
        for (int i = 0; i < n; i++) {
            int cx = (box[i][0] + box[i][2]) / 2, cy = (box[i][1] + box[i][3]) / 2;
            int hit = -1;
            for (int j = 0; j < n; j++)
                if (cx >= box[j][0] && cx <= box[j][2] && cy >= box[j][1] && cy <= box[j][3]) { hit = j; break; }
            if (hit != i && (hit < 0 || !act[hit].equals(act[i])))
                errors.add("centre of [" + act[i] + "] hits [" + (hit < 0 ? "nothing" : act[hit]) + "]");
        }

        if (errors.isEmpty()) {
            System.out.println("FaceplateMapTest: OK (" + n + " buttons, " + valid.size() + " valid TI keys)");
        } else {
            System.out.println("FaceplateMapTest: " + errors.size() + " FAILURE(S)");
            for (String e : errors) System.out.println("  - " + e);
            System.exit(1);
        }
    }

    static String validateAction(String a, Set<String> valid) {
        if (a.equals("CTRL") || a.equals("SHIFT")) return null;
        int c = a.indexOf(':');
        if (c < 0) return "no type prefix";
        String type = a.substring(0, c), p = a.substring(c + 1);
        if (type.equals("KEY")) return valid.contains(p) ? null : "'" + p + "' is not a TI key name";
        if (type.equals("ARROW")) return Arrays.asList("up","down","left","right","click").contains(p) ? null : "unknown arrow";
        if (type.equals("PALETTE")) return Arrays.asList("trig","pi","sym").contains(p) ? null : "unknown palette";
        return "unknown type '" + type + "'";
    }

    static Object field(Object o, String f) throws Exception {
        Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o);
    }
    static int intField(Object o, String f) throws Exception { return (Integer) field(o, f); }
}
