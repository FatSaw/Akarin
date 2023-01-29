package net.minecraft.server;

import org.apache.commons.lang3.Validate;

public class DataBits {

    private final long[] a;
    private final int b;
    private final long c;
    private final int d;

    public DataBits(int i, int j) {
        //Validate.inclusiveBetween(1L, 32L, (long) i); // Paper
        this.d = j;
        this.b = i;
        this.c = (1L << i) - 1L;
        this.a = new long[MathHelper.c(j * i, 64) / 64];
    }

    public void a(int i, int j) {
        //Validate.inclusiveBetween(0L, (long) (this.d - 1), (long) i); // Paper
        //Validate.inclusiveBetween(0L, this.c, (long) j); // Paper
        int k = i * this.b;
        int l = k >> 6;
        int i1 = (i + 1) * this.b - 1 >> 6;
        int j1 = k ^ l << 6;

        this.a[l] = this.a[l] & ~(this.c << j1) | ((long) j & this.c) << j1;
        if (l != i1) {
            int k1 = 64 - j1;
            int l1 = this.b - k1;

            this.a[i1] = this.a[i1] >>> l1 << l1 | ((long) j & this.c) >> k1;
        }

    }

    public int a(int i) {
        //Validate.inclusiveBetween(0L, (long) (this.d - 1), (long) i); // Paper
        int j = i * this.b;
        int k = j >> 6;
        int l = (i + 1) * this.b - 1 >> 6;
        int i1 = j ^ k << 6;

        if (k == l) {
            return (int) (this.a[k] >>> i1 & this.c);
        } else {
            int j1 = 64 - i1;

            return (int) ((this.a[k] >>> i1 | this.a[l] << j1) & this.c);
        }
    }

    public long[] getDataBits() { return this.a(); } // Paper - Anti-Xray - OBFHELPER
    public long[] a() {
        return this.a;
    }

    public int b() {
        return this.d;
    }
}
