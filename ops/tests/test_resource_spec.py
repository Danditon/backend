#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
# ─── How to run ───
# 1. Optional uv install: curl -LsSf https://astral.sh/uv/install.sh | sh
# 2. Run: python ops/tests/test_resource_spec.py [fixture.json]
#    Or: uv run ops/tests/test_resource_spec.py [fixture.json]
# ──────────────────
"""Reject drift from the approved normalized EC2 console contract, offline."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Final

ROOT: Final = Path(__file__).resolve().parents[2]
EXPECTED: Final = {
    "region": "ap-northeast-2",
    "instance_count": 1,
    "instance_name": "danditon-backend-prod",
    "instance_type": "t3.small",
    "purchase_option": "on-demand",
    "tenancy": "default",
    "ami_owner": "099720109477",
    "ami_name": "ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-20261017",
    "ami_architecture": "x86_64",
    "ami_state": "available",
    "root_volume_count": 1,
    "root_volume_type": "gp3",
    "root_volume_size_gib": 20,
    "root_volume_iops": 3000,
    "root_volume_throughput_mib_s": 125,
    "root_volume_encrypted": True,
    "root_volume_kms_key": "alias/aws/ebs",
    "root_volume_delete_on_termination": True,
    "cpu_credits": "standard",
    "metadata_endpoint": "enabled",
    "metadata_tokens": "required",
    "metadata_hop_limit": 1,
    "metadata_tags": "disabled",
    "iam_instance_profile": None,
    "user_data": "",
    "vpc_selection": "existing-default",
    "subnet_selection": "existing-default-public",
    "availability_zone": "ap-northeast-2a",
    "subnet_ipv4_cidr": "172.31.0.0/20",
    "route_destination": "0.0.0.0/0",
    "route_target": "internet-gateway-attached-to-selected-vpc",
    "route_state": "active",
    "auto_assign_public_ipv4": False,
    "ipv6_address_count": 0,
    "security_group_name": "danditon-backend-prod",
    "security_group_ingress": [
        {"protocol": "tcp", "from_port": 22, "to_port": 22, "ipv4_cidr": "0.0.0.0/0"},
        {"protocol": "tcp", "from_port": 80, "to_port": 80, "ipv4_cidr": "0.0.0.0/0"},
    ],
    "security_group_egress": [{"protocol": "-1", "ipv4_cidr": "0.0.0.0/0"}],
    "elastic_ip_count": 1,
    "elastic_ip_pool": "amazon",
    "elastic_ip_network_border_group": "ap-northeast-2",
    "elastic_ip_association": "instance-primary-private-ipv4",
    "key_pair_name": "danditon-operator-2026",
    "key_pair_method": "import-public-key-only",
    "key_pair_type": "ed25519",
    "ownership_tags": {
        "Project": "danditon", "Environment": "production",
        "Owner": "danditon", "ManagedBy": "manual-console",
    },
    "tagged_resource_types": [
        "instance", "volume", "network-interface", "security-group", "elastic-ip", "key-pair",
    ],
}


def check_spec(path: Path) -> None:
    """Parse and assert the entire fixture at the file boundary; never echo values."""
    # Given a normalized console specification, including intentionally public IPv4 SSH.
    source = path.read_text(encoding="utf-8-sig")
    spec = json.loads(source)
    assert isinstance(spec, dict), "resource_spec: expected JSON object"

    # When comparing each approved field independently of the editable JSON fixture.
    for field, expected in EXPECTED.items():
        assert field in spec, f"{field}: missing"
        actual = json.dumps(spec[field], sort_keys=True, allow_nan=False)
        required = json.dumps(expected, sort_keys=True, allow_nan=False)
        assert actual == required, f"{field}: differs from approved console specification"

    # Then extra resources/rules, missing fields and bool-as-integer changes fail closed.
    assert set(spec) == set(EXPECTED), "resource_spec: unexpected field"


def main() -> None:
    """Accept one optional disposable fixture path; perform no cloud operations."""
    assert len(sys.argv) <= 2, "usage: test_resource_spec.py [fixture.json]"
    path = Path(sys.argv[1]) if len(sys.argv) == 2 else ROOT / "infra/aws/expected-resources.json"
    check_spec(path)
    print("PASS: approved EC2 specification, including public IPv4 TCP22 and TCP80")


if __name__ == "__main__":
    main()
