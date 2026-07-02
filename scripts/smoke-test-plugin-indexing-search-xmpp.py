#!/usr/bin/env python3
"""
Send archived chat traffic for the monitoring plugin smoke test.

Requires: pip install slixmpp
"""

from __future__ import annotations

import argparse
import asyncio
import ssl
import sys

try:
    import slixmpp
except ImportError:
    print("ERROR: slixmpp is required. Install with: pip install slixmpp", file=sys.stderr)
    sys.exit(2)


class SmokeClient(slixmpp.ClientXMPP):
    def __init__(self, jid: str, password: str) -> None:
        super().__init__(jid, password)
        self.register_plugin("xep_0030")  # Service Discovery
        self.register_plugin("xep_0045")  # Multi-User Chat
        self.register_plugin("xep_0199")  # XMPP Ping
        self["feature_mechanisms"].unencrypted_plaintext = True
        self["feature_starttls"].unencrypted_plaintext = True
        self.ssl_context = ssl.create_default_context()
        self.ssl_context.check_hostname = False
        self.ssl_context.verify_mode = ssl.CERT_NONE
        self.done = asyncio.Event()

    async def wait_online(self, timeout: float = 30.0) -> None:
        await self.connect()
        await asyncio.wait_for(self.done.wait(), timeout=timeout)
        await self.disconnected

    def signal_ready(self) -> None:
        if not self.done.is_set():
            self.done.set()


async def exchange_direct_message(
    host: str,
    port: int,
    domain: str,
    user1: str,
    pass1: str,
    user2: str,
    pass2: str,
    token: str,
) -> None:
    recipient = f"{user2}@{domain}"
    body = f"smoke-test direct message needle={token}"

    client = SmokeClient(f"{user1}@{domain}", pass1)
    client.connect_address = (host, port)

    async def on_start(_event) -> None:
        client.send_presence()
        await client.get_roster()
        client.send_message(mto=recipient, mbody=body, mtype="chat")
        await asyncio.sleep(2)
        client.signal_ready()
        client.disconnect()

    client.add_event_handler("session_start", on_start)
    await client.wait_online()


async def send_muc_message(
    host: str,
    port: int,
    domain: str,
    user: str,
    password: str,
    room_local: str,
    token: str,
) -> str:
    muc_service = f"conference.{domain}"
    room_jid = f"{room_local}@{muc_service}"
    body = f"smoke-test muc message needle={token}"
    nick = "smoke"

    client = SmokeClient(f"{user}@{domain}", password)
    client.connect_address = (host, port)

    async def on_start(_event) -> None:
        client.send_presence()
        await client.get_roster()
        muc = client["xep_0045"]
        await muc.join_muc(room_jid, nick, maxhistory=0)
        await asyncio.sleep(1)
        client.send_message(mto=room_jid, mbody=body, mtype="groupchat")
        await asyncio.sleep(2)
        client.signal_ready()
        client.disconnect()

    client.add_event_handler("session_start", on_start)
    await client.wait_online()
    return room_jid


async def run(args: argparse.Namespace) -> None:
    await exchange_direct_message(
        args.host,
        args.port,
        args.domain,
        args.user1,
        args.pass1,
        args.user2,
        args.pass2,
        args.dm_token,
    )
    room_jid = await send_muc_message(
        args.host,
        args.port,
        args.domain,
        args.user1,
        args.pass1,
        args.room,
        args.muc_token,
    )
    print(f"direct-token={args.dm_token}")
    print(f"muc-token={args.muc_token}")
    print(f"muc-room={room_jid}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate archived XMPP traffic for smoke tests")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5222)
    parser.add_argument("--domain", default="localhost")
    parser.add_argument("--user1", default="smoke1")
    parser.add_argument("--pass1", default="smoke1")
    parser.add_argument("--user2", default="smoke2")
    parser.add_argument("--pass2", default="smoke2")
    parser.add_argument("--room", default="smoke-room")
    parser.add_argument("--dm-token", required=True)
    parser.add_argument("--muc-token", required=True)
    args = parser.parse_args()

    asyncio.run(run(args))


if __name__ == "__main__":
    main()
