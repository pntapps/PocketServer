#!/bin/bash
source /opt/pocketserver/lib.sh
export npm_config_update_notifier=false npm_config_fund=false npm_config_audit=false

step "Creating the React starter (Vite)"
mkdir -p /root/projects
# Only when missing: never overwrite the user's work.
# Built under a temporary name and moved into place only when complete, so an interrupted install
# (heat stop, app killed) is redone next time instead of leaving a broken starter behind.
if [ ! -e /root/projects/react-starter ]; then
    rm -rf /root/projects/.build/react-starter && mkdir -p /root/projects/.build
    cd /root/projects/.build
    npm create -y vite@latest react-starter -- --template react --no-interactive </dev/null
    cd react-starter
    step "Installing dependencies"
    npm install --no-progress
    # Listen on the Wi-Fi too, so other devices can open the dev server.
    npm pkg set scripts.dev="vite --host 0.0.0.0" scripts.preview="vite preview --host 0.0.0.0"
    # Preview inside Code Studio: code-server proxies the port under /absproxy/5173/, and Vite's
    # absolute asset paths only resolve there when it knows that base.
    npm pkg set scripts.dev:studio="vite --host 0.0.0.0 --base /absproxy/5173/"
    cd /root/projects
    mv .build/react-starter react-starter
    rmdir .build 2>/dev/null || true
fi

cd /root/projects/react-starter
reset_credentials
set_credential React "$(node -p "require('react/package.json').version")"
set_credential Vite "$(node -p "require('vite/package.json').version")"
set_credential "Run React" "cd ~/projects/react-starter && npm run dev"
set_credential "Run in Code Studio" "npm run dev:studio, then open /absproxy/5173/ in Code Studio's address"
set_credential "New project" "npm create vite@latest myapp -- --template react"
