#!/system/bin/sh
MODDIR=${0%/*}

if [ ! -f "$MODDIR/target.txt" ]; then
    echo "com.roblox.client" > "$MODDIR/target.txt"
fi

chmod 0644 "$MODDIR/target.txt" 2>/dev/null
