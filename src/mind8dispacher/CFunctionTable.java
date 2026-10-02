package mind8dispacher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CFunctionTable {
    public static final int FIRST_NUMBER = 0x0010;
    public static final int LAST_NUMBER = 0x02F6;
    public static final int TABLE_SIZE = LAST_NUMBER - FIRST_NUMBER + 1;

    public enum Comparison {
        MATCH,
        C_ONLY,
        JAVA_ONLY,
        NAME_MISMATCH,
        INTENTIONAL_ALIAS
    }

    public record CEntry(int number, String name) {
        public CEntry {
            checkNumber(number);
            Objects.requireNonNull(name, "name");
        }
    }

    public record Difference(int number, String cName, String javaName,
            Comparison comparison) {
    }

    private final Entry[] entries = new Entry[TABLE_SIZE];
    private int registeredCount;

    public void register(int number, String name, Runnable handler) {
        register(number, name, handler, -1);
    }

    public void registerAlias(int number, String name, int canonicalNumber) {
        checkNumber(canonicalNumber);
        Entry canonical = entries[canonicalNumber - FIRST_NUMBER];
        if (canonical == null) {
            throw new IllegalArgumentException(String.format(
                    "Alias target 0x%04X is not registered", canonicalNumber));
        }
        register(number, name, canonical.handler, canonicalNumber);
    }

    public void invoke(int number) {
        Entry entry = getEntry(number);
        if (entry == null) {
            throw new IllegalStateException(String.format(
                    "C function 0x%04X is not registered", number));
        }
        entry.handler.run();
    }

    public int registeredCount() {
        return registeredCount;
    }

    public List<Difference> compare(List<CEntry> cEntries) {
        Objects.requireNonNull(cEntries, "cEntries");
        CEntry[] cTable = new CEntry[TABLE_SIZE];
        for (CEntry cEntry : cEntries) {
            Objects.requireNonNull(cEntry, "cEntries contains null");
            int index = cEntry.number() - FIRST_NUMBER;
            if (cTable[index] != null) {
                throw new IllegalArgumentException(String.format(
                        "Duplicate C function number 0x%04X", cEntry.number()));
            }
            cTable[index] = cEntry;
        }

        List<Difference> differences = new ArrayList<>();
        for (int index = 0; index < TABLE_SIZE; index++) {
            int number = FIRST_NUMBER + index;
            CEntry cEntry = cTable[index];
            Entry javaEntry = entries[index];
            if (cEntry == null && javaEntry == null) {
                continue;
            }
            Comparison comparison;
            if (cEntry == null) {
                comparison = Comparison.JAVA_ONLY;
            } else if (javaEntry == null) {
                comparison = Comparison.C_ONLY;
            } else if (javaEntry.aliasOf >= 0 && cEntry.name().equals(javaEntry.name)) {
                comparison = Comparison.INTENTIONAL_ALIAS;
            } else if (!cEntry.name().equals(javaEntry.name)) {
                comparison = Comparison.NAME_MISMATCH;
            } else {
                comparison = Comparison.MATCH;
            }
            differences.add(new Difference(number,
                    cEntry == null ? null : cEntry.name(),
                    javaEntry == null ? null : javaEntry.name, comparison));
        }
        return List.copyOf(differences);
    }

    private void register(int number, String name, Runnable handler, int aliasOf) {
        checkNumber(number);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        int index = number - FIRST_NUMBER;
        if (entries[index] != null) {
            throw new IllegalArgumentException(String.format(
                    "C function number 0x%04X is already registered", number));
        }
        entries[index] = new Entry(name, handler, aliasOf);
        registeredCount++;
    }

    private Entry getEntry(int number) {
        checkNumber(number);
        return entries[number - FIRST_NUMBER];
    }

    private static void checkNumber(int number) {
        if (number < FIRST_NUMBER || number > LAST_NUMBER) {
            throw new IllegalArgumentException(String.format(
                    "C function number 0x%04X is outside 0x%04X..0x%04X",
                    number, FIRST_NUMBER, LAST_NUMBER));
        }
    }

    private record Entry(String name, Runnable handler, int aliasOf) {
    }
}