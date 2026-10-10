# Shared scan primitive. Product scripts choose their own tracked-path policy.
reject_tracked_credential_literal() {
    if git -c grep.threads=2 grep -I --quiet --extended-regexp "$2" -- .; then
        echo "credential containment smoke failed: $1" >&2
        exit 1
    else
        scan_status=$?
        if [ "$scan_status" -ne 1 ]; then
            echo "credential containment smoke failed: tracked-text scan exited $scan_status" >&2
            exit "$scan_status"
        fi
    fi
}
