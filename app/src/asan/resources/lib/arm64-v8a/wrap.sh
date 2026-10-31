#!/system/bin/sh
HERE="$(dirname "$0")"
# Keep quarantine modest on the 2 GB test phone. Do not continue after an error:
# the first report, not a later corrupted pointer, is the evidence we need.
export ASAN_OPTIONS="halt_on_error=1:abort_on_error=1:detect_leaks=0:quarantine_size_mb=4:thread_local_quarantine_size_kb=64:malloc_context_size=20:log_to_syslog=false:allow_user_segv_handler=0"
export LD_PRELOAD="$HERE/libclang_rt.asan-aarch64-android.so:$HERE/libc++_shared.so${LD_PRELOAD:+:$LD_PRELOAD}"
# On an existing installation storage permission is retained. A first install
# must still be able to open the permission screen if this path is unavailable.
REPORT=/storage/emulated/0/GS-SAMP/asan.txt
if mkdir -p /storage/emulated/0/GS-SAMP 2>/dev/null && ( : >> "$REPORT" ) 2>/dev/null; then
    echo "=== ASAN process start ===" >> "$REPORT"
    exec "$@" 2>> "$REPORT"
else
    exec "$@"
fi
