package dev.tim9h.javafxblur2;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javafx.stage.Stage;

/**
 * Applies a native Windows compositor backdrop (the frosted "milk glass" look,
 * like the Windows Start menu) to a JavaFX {@link Stage}.
 *
 * <p>
 * The effect is produced by calling native Win32 APIs through the Java Foreign
 * Function &amp; Memory API ({@link java.lang.foreign}), which is a finalized
 * API since Java&nbsp;22:
 * <ul>
 * <li>{@link Effect#ACRYLIC} uses the (undocumented but widely used) {@code
 *       user32!SetWindowCompositionAttribute} with an
 * {@code ACCENT_ENABLE_ACRYLICBLURBEHIND} accent policy. Works on
 * Windows&nbsp;10 and 11.</li>
 * <li>{@link Effect#MICA} uses {@code dwmapi!DwmSetWindowAttribute} with
 * {@code DWMWA_SYSTEMBACKDROP_TYPE = DWMSBT_MAINWINDOW}. Requires
 * Windows&nbsp;11 (build&nbsp;22000+).</li>
 * </ul>
 *
 * <p>
 * For the effect to be visible the stage must not paint an opaque client area:
 * use {@link javafx.stage.StageStyle#TRANSPARENT}, a transparent {@code Scene}
 * fill and (semi-)transparent node backgrounds.
 *
 * <p>
 * All native access is guarded by an operating-system check. On non-Windows
 * platforms, or when a native call fails, the methods are a no-op and return
 * {@code false}, so callers remain portable.
 *
 * <p>
 * Call {@link #apply(Stage, Effect)} on the JavaFX Application Thread
 * <em>after</em> {@link Stage#show()} (the native window handle only exists
 * once the stage is shown).
 */
public final class WindowsBackdrop {

	/** Backdrop effect variants. */
	public enum Effect {
		/** Frosted, blurred "milk glass" look (Windows 10/11). */
		ACRYLIC,
		/** Subtle wallpaper-tinted material (Windows 11 only). */
		MICA
	}

	/**
	 * Ready-made light "milk glass" tint (0xAARRGGBB), like the light Start menu.
	 */
	public static final int LIGHT_TINT = 0x40FFFFFF;

	/** Ready-made dark tint (0xAARRGGBB), like the dark Start menu. */
	public static final int DARK_TINT = 0x40202020;

	private static final int DEFAULT_TINT_ARGB = DARK_TINT;

	// user32 WINDOWCOMPOSITIONATTRIB
	private static final int WCA_ACCENT_POLICY = 19;
	// ACCENT_STATE
	private static final int ACCENT_DISABLED = 0;
	private static final int ACCENT_ENABLE_ACRYLICBLURBEHIND = 4;
	// GetWindowLongPtr index / extended window styles
	private static final int GWL_EXSTYLE = -20;
	private static final long WS_EX_LAYERED = 0x00080000L;
	// dwmapi DWMWINDOWATTRIBUTE / DWM_SYSTEMBACKDROP_TYPE
	private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
	private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
	private static final int DWMWCP_ROUND = 2;
	private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;
	private static final int DWMSBT_NONE = 1;
	private static final int DWMSBT_MAINWINDOW = 2;
	/** Mica requires Windows 11. */
	private static final int MICA_MIN_BUILD = 22000;

	private static final int DWMWCP_DONOTROUND = 1;

	private static final int SWP_NOSIZE = 0x0001;
	private static final int SWP_NOMOVE = 0x0002;
	private static final int SWP_NOZORDER = 0x0004;
	private static final int SWP_NOACTIVATE = 0x0010;
	private static final int SWP_FRAMECHANGED = 0x0020;

	private static final int RDW_INVALIDATE = 0x0001;
	private static final int RDW_ERASE = 0x0004;
	private static final int RDW_FRAME = 0x0400;
	private static final int RDW_ALLCHILDREN = 0x0080;

	private WindowsBackdrop() {
	}

	/**
	 * Applies the default {@link Effect#ACRYLIC} backdrop with the default
	 * milky-white tint.
	 *
	 * @return {@code true} if the effect was applied.
	 */
	public static boolean apply(Stage stage) {
		return apply(stage, Effect.ACRYLIC, DEFAULT_TINT_ARGB);
	}

	/**
	 * Applies the default {@link Effect#ACRYLIC} backdrop with the given tint.
	 *
	 * @param tintArgb tint and opacity of the frosted layer as {@code 0xAARRGGBB}.
	 * @return {@code true} if the effect was applied.
	 */
	public static boolean apply(Stage stage, int tintArgb) {
		return apply(stage, Effect.ACRYLIC, tintArgb);
	}

	/**
	 * Applies the given effect using the default milky-white tint.
	 *
	 * @return {@code true} if the effect was applied.
	 */
	public static boolean apply(Stage stage, Effect effect) {
		return apply(stage, effect, DEFAULT_TINT_ARGB);
	}

	/**
	 * Applies the given effect.
	 *
	 * @param stage    the shown JavaFX stage.
	 * @param effect   the backdrop effect.
	 * @param tintArgb tint and opacity of the frosted layer as {@code 0xAARRGGBB}.
	 *                 Used as the acrylic gradient color for ACRYLIC; for both
	 *                 effects its luminance also selects the DWM light/dark mode (a
	 *                 dark tint like {@link #DARK_TINT} yields a dark
	 *                 frame/material).
	 * @return {@code true} if the effect was applied, {@code false} on non-Windows
	 *         or on failure.
	 */
	public static boolean apply(Stage stage, Effect effect, int tintArgb) {
		if (stage == null || effect == null || !isWindows()) {
			return false;
		}
		try {
			return Native.instance().apply(stage, effect, tintArgb);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Clips the stage's window to a rounded rectangle so the window (and the
	 * acrylic backdrop) has rounded corners. Works on the layered window created by
	 * a {@code StageStyle.TRANSPARENT} stage.
	 *
	 * <p>
	 * Call after {@link Stage#show()} and re-call when the window is resized.
	 * Radius is in logical pixels and is scaled by the stage's output scale for
	 * HiDPI.
	 *
	 * @param stage    the shown JavaFX stage.
	 * @param radiusPx corner radius in logical pixels.
	 * @return {@code true} if the rounded region was applied.
	 */
	public static boolean roundCorners(Stage stage, int radiusPx) {
		if (stage == null || radiusPx < 0 || !isWindows()) {
			return false;
		}
		try {
			return Native.instance().roundCorners(stage, radiusPx);
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean clearRoundedCorners(Stage stage) {
		if (stage == null || !isWindows()) {
			return false;
		}

		try {
			return Native.instance().clearRoundedCorners(stage);
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	/**
	 * Holds the native bindings and performs the calls. Created once and reused.
	 */
	private static final class Native {

		private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
		private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
		private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

		private static Native instance;

		/**
		 * Native bindings and scratch allocations live for the whole JVM (GC-managed).
		 */
		private final Arena arena = Arena.ofAuto();
		private final Linker linker = Linker.nativeLinker();

		/**
		 * Cache of resolved native window handles per stage, so resize updates don't
		 * re-resolve.
		 */
		private final java.util.Map<Stage, Long> hwndCache = new java.util.WeakHashMap<>();

		private final MethodHandle findWindow;
		private final MethodHandle setWindowCompositionAttribute;
		private final MethodHandle getCurrentProcessId;
		private final MethodHandle enumWindows;
		private final MethodHandle getWindowThreadProcessId;
		private final MethodHandle getClassName;
		private final MethodHandle isWindow;
		private final MethodHandle isWindowVisible;
		private final MethodHandle getWindowLongPtr;
		private final MethodHandle redrawWindow;
		private final MethodHandle getWindowRect;
		private final MethodHandle setWindowRgn;
		private final MethodHandle createRoundRectRgn;
		private final MethodHandle dwmSetWindowAttribute;
		private final MethodHandle dwmExtendFrameIntoClientArea;
		private final MethodHandle rtlGetVersion;
		private final MethodHandle setWindowPos;

		static synchronized Native instance() {
			if (instance == null) {
				instance = new Native();
			}
			return instance;
		}

		Native() {
			SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
			SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
			SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", arena);
			SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi", arena);
			SymbolLookup ntdll = SymbolLookup.libraryLookup("ntdll", arena);

			var ADDRESS = ValueLayout.ADDRESS;

			findWindow = linker.downcallHandle(user32.find("FindWindowW").orElseThrow(),
					FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
			setWindowCompositionAttribute = linker.downcallHandle(
					user32.find("SetWindowCompositionAttribute").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS));
			getCurrentProcessId = linker.downcallHandle(kernel32.find("GetCurrentProcessId").orElseThrow(),
					FunctionDescriptor.of(INT));
			enumWindows = linker.downcallHandle(user32.find("EnumWindows").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, LONG));
			getWindowThreadProcessId = linker.downcallHandle(user32.find("GetWindowThreadProcessId").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS));
			getClassName = linker.downcallHandle(user32.find("GetClassNameW").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT));
			isWindowVisible = linker.downcallHandle(user32.find("IsWindowVisible").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS));
			isWindow = linker.downcallHandle(user32.find("IsWindow").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS));
			getWindowLongPtr = linker.downcallHandle(user32.find("GetWindowLongPtrW").orElseThrow(),
					FunctionDescriptor.of(LONG, ADDRESS, INT));
			redrawWindow = linker.downcallHandle(user32.find("RedrawWindow").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS, ADDRESS, INT));
			getWindowRect = linker.downcallHandle(user32.find("GetWindowRect").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS));
			setWindowRgn = linker.downcallHandle(user32.find("SetWindowRgn").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT));
			createRoundRectRgn = linker.downcallHandle(gdi32.find("CreateRoundRectRgn").orElseThrow(),
					FunctionDescriptor.of(ADDRESS, INT, INT, INT, INT, INT));
			dwmSetWindowAttribute = linker.downcallHandle(dwmapi.find("DwmSetWindowAttribute").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, INT, ADDRESS, INT));
			dwmExtendFrameIntoClientArea = linker.downcallHandle(
					dwmapi.find("DwmExtendFrameIntoClientArea").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS));
			rtlGetVersion = linker.downcallHandle(ntdll.find("RtlGetVersion").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS));
			setWindowPos = linker.downcallHandle(user32.find("SetWindowPos").orElseThrow(),
					FunctionDescriptor.of(INT, ADDRESS, ADDRESS, INT, INT, INT, INT, INT));
		}

		boolean apply(Stage stage, Effect effect, int tintArgb) throws Throwable {
			MemorySegment hwnd = resolveHwnd(stage);
			if (hwnd == null || hwnd.address() == 0) {
				return false;
			}
			// Derive dark mode from the tint's luminance so a dark tint yields a dark
			// frame/material.
			setImmersiveDarkMode(hwnd, isDark(tintArgb));
			return switch (effect) {
			case ACRYLIC -> applyAcrylic(hwnd, tintArgb);
			case MICA -> applyMica(hwnd);
			};
		}

		boolean roundCorners(Stage stage, int radiusPx) throws Throwable {
			MemorySegment hwnd = resolveHwnd(stage);
			if (hwnd == null || hwnd.address() == 0) {
				return false;
			}
			// Win11 hint (rounds the DWM frame if the window is not layered); harmless
			// otherwise.
			MemorySegment pref = arena.allocate(4);
			pref.set(INT, 0, DWMWCP_ROUND);
			dwmSetWindowAttribute.invoke(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, pref, 4);

			// Real mechanism for a layered/transparent window: clip it to a rounded region,
			// which
			// also clips the acrylic blur.
			MemorySegment rect = arena.allocate(16); // RECT { int left, top, right, bottom; }
			if ((int) getWindowRect.invoke(hwnd, rect) == 0) {
				return false;
			}
			int width = rect.get(INT, 8) - rect.get(INT, 0);
			int height = rect.get(INT, 12) - rect.get(INT, 4);

			int radius = Math.round(radiusPx * (float) stage.getOutputScaleX());
			int diameter = Math.min(2 * radius, Math.min(width, height));

			MemorySegment rgn = (MemorySegment) createRoundRectRgn.invoke(0, 0, width + 1, height + 1, diameter,
					diameter);
			if (rgn == null || rgn.address() == 0) {
				return false;
			}
			// The system owns the region after this call; do not delete it.
			int ok = (int) setWindowRgn.invoke(hwnd, rgn, 1);
			return ok != 0;
		}

		boolean clearRoundedCorners(Stage stage) throws Throwable {
			MemorySegment hwnd = resolveHwnd(stage);

			if (hwnd == null || hwnd.address() == 0) {
				return false;
			}

			// Remove the explicit HRGN assigned by SetWindowRgn().
			int regionResult = (int) setWindowRgn.invoke(hwnd, MemorySegment.NULL, 1);

			// Reset the DWM corner preference.
			MemorySegment preference = arena.allocate(4);
			preference.set(INT, 0, DWMWCP_DONOTROUND);

			int cornerResult = (int) dwmSetWindowAttribute.invoke(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, preference, 4);

			// Force Windows to recalculate and repaint the frame.
			setWindowPos.invoke(hwnd, MemorySegment.NULL, 0, 0, 0, 0,
					SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);

			redrawWindow.invoke(hwnd, MemorySegment.NULL, MemorySegment.NULL,
					RDW_INVALIDATE | RDW_ERASE | RDW_FRAME | RDW_ALLCHILDREN);

			return regionResult != 0 || cornerResult == 0;
		}

		private void setImmersiveDarkMode(MemorySegment hwnd, boolean dark) throws Throwable {
			MemorySegment value = arena.allocate(4);
			value.set(INT, 0, dark ? 1 : 0);
			dwmSetWindowAttribute.invoke(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4);
		}

		private boolean applyAcrylic(MemorySegment hwnd, int tintArgb) throws Throwable {
			// Clear any previously set Mica/DWM system backdrop so it does not linger under
			// the acrylic.
			setSystemBackdrop(hwnd, DWMSBT_NONE);

			// struct ACCENT_POLICY { int AccentState; int AccentFlags; int GradientColor;
			// int AnimationId; }
			MemorySegment accent = arena.allocate(16);
			accent.set(INT, 0, ACCENT_ENABLE_ACRYLICBLURBEHIND);
			accent.set(INT, 4, 0);
			accent.set(INT, 8, argbToAbgr(tintArgb));
			accent.set(INT, 12, 0);

			return setAccentPolicy(hwnd, accent);
		}

		private boolean applyMica(MemorySegment hwnd) throws Throwable {
			// Mica is a DWM system backdrop: it requires Windows 11 (build 22000+) and a
			// window that is
			// composed by DWM. It does NOT render on layered windows (which is what JavaFX
			// creates for a
			// StageStyle.TRANSPARENT stage), so bail out honestly instead of showing a
			// blank grey window.
			if (windowsBuild() < MICA_MIN_BUILD || isLayered(hwnd)) {
				return false;
			}

			// Disable any active acrylic accent policy; otherwise it overdraws the Mica
			// material.
			MemorySegment off = arena.allocate(16);
			off.set(INT, 0, ACCENT_DISABLED);
			setAccentPolicy(hwnd, off);

			// Extend the frame so the material fills the whole client area.
			MemorySegment margins = arena.allocate(16); // MARGINS { int l, r, t, b; }
			margins.set(INT, 0, -1);
			margins.set(INT, 4, -1);
			margins.set(INT, 8, -1);
			margins.set(INT, 12, -1);
			dwmExtendFrameIntoClientArea.invoke(hwnd, margins);

			int hr = setSystemBackdrop(hwnd, DWMSBT_MAINWINDOW);
			// RDW_INVALIDATE | RDW_ERASE | RDW_FRAME | RDW_ALLCHILDREN
			redrawWindow.invoke(hwnd, MemorySegment.NULL, MemorySegment.NULL, 0x485);
			return hr == 0; // S_OK
		}

		/**
		 * True if the window has the WS_EX_LAYERED extended style (per-pixel alpha, no
		 * DWM backdrop).
		 */
		private boolean isLayered(MemorySegment hwnd) throws Throwable {
			long exStyle = (long) getWindowLongPtr.invoke(hwnd, GWL_EXSTYLE);
			return (exStyle & WS_EX_LAYERED) != 0;
		}

		/**
		 * Reads the real Windows build number via ntdll!RtlGetVersion (GetVersionEx
		 * lies for apps).
		 */
		private int windowsBuild() throws Throwable {
			// RTL_OSVERSIONINFOW: DWORD size, major, minor, build, platformId, WCHAR
			// csd[128] = 276 bytes.
			MemorySegment info = arena.allocate(276);
			info.set(INT, 0, 276);
			rtlGetVersion.invoke(info);
			return info.get(INT, 12); // dwBuildNumber
		}

		/** Sets a WCA_ACCENT_POLICY on the window. */
		private boolean setAccentPolicy(MemorySegment hwnd, MemorySegment accent) throws Throwable {
			// struct WINDOWCOMPOSITIONATTRIBDATA { int Attrib; <pad> void* pvData; size_t
			// cbData; }
			MemorySegment data = arena.allocate(24);
			data.set(INT, 0, WCA_ACCENT_POLICY);
			data.set(ValueLayout.ADDRESS, 8, accent);
			data.set(LONG, 16, 16L);
			int ok = (int) setWindowCompositionAttribute.invoke(hwnd, data);
			return ok != 0;
		}

		/** Sets DWMWA_SYSTEMBACKDROP_TYPE; returns the HRESULT. */
		private int setSystemBackdrop(MemorySegment hwnd, int type) throws Throwable {
			MemorySegment value = arena.allocate(4);
			value.set(INT, 0, type);
			return (int) dwmSetWindowAttribute.invoke(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, value, 4);
		}

		/**
		 * Resolves the native {@code HWND} for the stage, caching the result so
		 * repeated calls (e.g. per animation frame on resize) are cheap and do not
		 * touch the window title. Primary strategy: temporarily set a unique window
		 * title and look it up with {@code FindWindowW}. Fallback: enumerate top-level
		 * windows of the current process whose class name starts with the JavaFX Glass
		 * window class.
		 */
		private MemorySegment resolveHwnd(Stage stage) throws Throwable {
			Long cached = hwndCache.get(stage);
			if (cached != null && cached != 0 && (int) isWindow.invoke(MemorySegment.ofAddress(cached)) != 0) {
				return MemorySegment.ofAddress(cached);
			}

			MemorySegment hwnd = null;
			String original = stage.getTitle();
			String marker = "embeddedmap-backdrop-" + UUID.randomUUID();
			stage.setTitle(marker);
			try {
				MemorySegment found = (MemorySegment) findWindow.invoke(MemorySegment.NULL, wide(marker));
				if (found != null && found.address() != 0) {
					hwnd = found;
				}
			} finally {
				stage.setTitle(original == null ? "" : original);
			}
			if (hwnd == null) {
				hwnd = enumerateByClass();
			}
			if (hwnd != null && hwnd.address() != 0) {
				hwndCache.put(stage, hwnd.address());
			}
			return hwnd;
		}

		private MemorySegment enumerateByClass() throws Throwable {
			int pid = (int) getCurrentProcessId.invoke();
			AtomicLong result = new AtomicLong(0);

			EnumProc proc = (hwndAddr, _) -> {
				try {
					MemorySegment hwnd = MemorySegment.ofAddress(hwndAddr);
					if ((int) isWindowVisible.invoke(hwnd) == 0) {
						return 1;
					}
					MemorySegment pidOut = arena.allocate(4);
					getWindowThreadProcessId.invoke(hwnd, pidOut);
					if (pidOut.get(INT, 0) != pid) {
						return 1;
					}
					if (className(hwnd).startsWith("GlassWndClass")) {
						result.set(hwndAddr);
						return 0; // stop enumeration
					}
				} catch (Throwable ignored) {
					// keep enumerating
				}
				return 1;
			};

			MethodHandle target = MethodHandles.lookup()
					.findVirtual(EnumProc.class, "callback", MethodType.methodType(int.class, long.class, long.class))
					.bindTo(proc);
			MemorySegment stub = linker.upcallStub(target, FunctionDescriptor.of(INT, LONG, LONG), arena);

			enumWindows.invoke(stub, 0L);

			long addr = result.get();
			return addr == 0 ? null : MemorySegment.ofAddress(addr);
		}

		private String className(MemorySegment hwnd) throws Throwable {
			int cap = 256;
			MemorySegment buf = arena.allocate((long) cap * 2);
			int len = (int) getClassName.invoke(hwnd, buf, cap);
			if (len <= 0) {
				return "";
			}
			byte[] bytes = new byte[len * 2];
			MemorySegment.copy(buf, BYTE, 0, bytes, 0, len * 2);
			return new String(bytes, StandardCharsets.UTF_16LE);
		}

		/**
		 * Allocates a null-terminated UTF-16LE (wide) string for Win32 {@code W} APIs.
		 */
		private MemorySegment wide(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
			MemorySegment seg = arena.allocate(bytes.length + 2L); // zero-filled -> null terminator
			MemorySegment.copy(bytes, 0, seg, BYTE, 0, bytes.length);
			return seg;
		}

		/**
		 * Converts 0xAARRGGBB to the 0xAABBGGRR layout expected by
		 * ACCENT_POLICY.GradientColor.
		 */
		private static int argbToAbgr(int argb) {
			int a = (argb >>> 24) & 0xFF;
			int r = (argb >>> 16) & 0xFF;
			int g = (argb >>> 8) & 0xFF;
			int b = argb & 0xFF;
			return (a << 24) | (b << 16) | (g << 8) | r;
		}

		/**
		 * True when the RGB part of the 0xAARRGGBB tint is dark (perceived luminance
		 * &lt; 0.5).
		 */
		private static boolean isDark(int argb) {
			int r = (argb >>> 16) & 0xFF;
			int g = (argb >>> 8) & 0xFF;
			int b = argb & 0xFF;
			double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
			return luminance < 0.5;
		}
	}

	/** Functional interface backing the {@code EnumWindows} upcall stub. */
	@FunctionalInterface
	private interface EnumProc {
		int callback(long hwnd, long lParam);
	}
}