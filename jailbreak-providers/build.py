"""Offline MapleSU provider build with input/output identity.

Every invocation verifies the retained upstream bytes first, then configures one
explicit target set. Products land in ``<output>/jailbreak-product`` and the
report records their identity; nothing here installs, stages or runs anything.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import shutil
import os
import re
from pathlib import Path
import subprocess
import sys

from verify_sources import verify

ROOT = Path(__file__).resolve().parent
PRODUCT_DIR = "jailbreak-product"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    return {path.relative_to(ROOT).as_posix(): sha(path)
            for path in sorted(ROOT.rglob("*")) if path.is_file()
            and "__pycache__" not in path.parts and "build" not in path.parts}


def products(output):
    found = []
    candidates = sorted((output / "build" / PRODUCT_DIR).glob("*")) if \
        (output / "build" / PRODUCT_DIR).is_dir() else []
    for name in ("libmaplesu_root_consumer.so",):
        candidates.append(output / "build/lease" / name)
    for path in sorted((output / "build/ghostlock").glob("*.so")):
        candidates.append(path)
    candidates.append(output / "extractor/libmaplesu_ghostlock_extract.so")
    for path in candidates:
        if path.is_file():
            found.append({"name": path.name, "path": str(path), "sha256": sha(path),
                          "bytes": path.stat().st_size})
    return found


# --- root-loader canary packaging (step 2; re-pointed in step 3) ------------- #
#
# Default builds ship the ir-loader module and its loader-contract canary. With
# --root-canary the product IR slot instead carries a non-ELF stub (so
# `install_ir()` fails at the .ir_spec lookup instead of ever loading a
# per-release kernel-text patcher) and the canary slot carries the freshly built
# maplesu_root.ko.
#
# Step-3 semantic change (design maplesu-step3-umh-mode-design-2026-10-29 §3.4):
# the module now execs `<CE>/bs` -- the bootstrap the App already stages on every
# exploit route -- so the canary slot delivers the *hook module* and there is no
# separate 5.5 KB canary helper asset any more. One binary, one path, two modes.
# The slot keeps its old name (`--root-canary`) because renaming the CLI would
# touch every runbook; the record says what it now means.
ROOT_CANARY_DIR = "root-canary"
# Ultra target: the release-independent root module the UMH helper loads.
ROOT_DIR = "root"
# L3 target (ABI v1, 2026-10-31): the in-kernel su gate. The UMH helper loads it
# into the attempt's stage-2 slot (`<CE>/maplesu_stage2.ko`, bootstrap.c
# MAPLESU_STAGE2_NAME) with finit_module, inside the same window the root module
# opened. It is built by the same builder and from the same contract as the root
# module - one vermagic and one KCFI id set - so a drift fails the build rather
# than the device.
L3_DIR = "l3"
L3_MODULE = "maplesu_l3"

# Every kernel family the product claims to support (5.10 .. 6.18). The manager's
# admission table carries the same eight, and tools/check_kmi_matrix.py asserts the
# two agree: "supports all models" is only true while they do.
KMI_TABLE = (
    (12, 5, 10, "android12-5.10"), (13, 5, 10, "android13-5.10"),
    (13, 5, 15, "android13-5.15"), (14, 5, 15, "android14-5.15"),
    (14, 6, 1, "android14-6.1"), (15, 6, 6, "android15-6.6"),
    (16, 6, 12, "android16-6.12"), (17, 6, 18, "android17-6.18"),
)


def kmi_of(release):
    """(android api, kernel major.minor) -> KMI string, or None when unsupported."""
    version = re.search(r"(\d+)\.(\d+)", release or "")
    android = re.search(r"android(\d+)", release or "")
    if not version or not android:
        return None
    key = (int(android.group(1)), int(version.group(1)), int(version.group(2)))
    for api, major, minor, kmi in KMI_TABLE:
        if (api, major, minor) == key:
            return kmi
    return None


def dirtyfrag_modules(output):
    """Record the actual .incbin inputs, including caller-provided overrides."""
    directory = output / "build/dirtyfrag/product-stage/ko"
    native = (output / "build" / PRODUCT_DIR / "libdfroot.so").read_bytes()
    result = {}
    for _, _, _, kmi in KMI_TABLE:
        path = directory / ("dfroot-" + kmi + ".ko")
        if path.read_bytes() not in native:
            raise ValueError("DirtyFrag native artifact does not embed " + kmi)
        result[kmi] = {"path": str(path), "sha256": sha(path), "bytes": path.stat().st_size}
    return result
ROOT_CANARY_STUB_NAME = "maplesu_ir-stub.ko"
# The module's fixed helper constant (maplesu_root.c MAPLESU_HELPER_PATH): the
# credential-encrypted `bs` the App stages with DirtyfragAttempt.stageBootstrap().
# Duplicated here on purpose - a drift between the module and the App's staging
# path must fail this build rather than the device run.
ROOT_CANARY_HELPER_PATH = "/data/user/0/dev.maplesu.manager/bs"
ROOT_CANARY_STUB_BYTES = (
    b"MAPLESU IR STUB -- deliberately not an ELF image and never loaded.\n"
    b"It occupies the product in-kernel slot only so install_ir() stops at the\n"
    b"spec-section lookup (MAPLESU_IR_MODULE_INVALID) instead of loading a\n"
    b"per-release kernel-text patcher. The canary module lives in the canary\n"
    b"slot; see docs/patching/maplesu-canary-packaging-2026-10-29.md and\n"
    b"docs/patching/maplesu-step3-implementation-2026-10-29.md.\n")
# Present in the module unconditionally since the canary-hardening pass: the two
# positive printks that make the kernel side of acceptance (b)/(c) readable. Without
# them the only kernel-side evidence is the module's return value, which on this
# image is not evidence at all (the four-argument wrapper returns 0 silently).
ROOT_CANARY_PRINTKS = (b"kallsyms_lookup_name=", b"umh setup=", b"exec=%px ret=")
# The handover shape the module must report: it resolved the two-stage pair and
# writes subprocess_info.path. `wrapper-only` means the module carries only the
# four-argument call, which CONFIG_STATIC_USERMODEHELPER_PATH="" turns into a silent
# no-op -- the false positive this packaging edge refuses to ship.
ROOT_CANARY_HANDOVER = "setup-exec"


def _log_command(command, log_path):
    result = subprocess.run(command, capture_output=True, text=True)
    with log_path.open("a", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        log.write(result.stdout or "")
        log.write(result.stderr or "")
    return result.returncode


def ir_specs(report):
    """The packaged per-release spec list (the App's adaptation list)."""
    specs = []
    for record_path in sorted((ROOT / "ir-loader" / "specs").glob("*.ir.spec.json")):
        record = json.loads(record_path.read_text(encoding="utf-8"))
        spec_path = Path(record["spec"])
        if not spec_path.is_absolute():
            spec_path = (ROOT.parent / spec_path).resolve()
        if not spec_path.is_file():
            report["ir"] = {"error": "missing generated spec " + record["spec"]}
            return None
        specs.append({
            "release": record["release"],
            "path": str(spec_path),
            "sha256": sha(spec_path),
            "bytes": spec_path.stat().st_size,
            "imageSha256": record["imageSha256"],
            "blocks": [block["id"] for block in record["blocks"]],
        })
    return specs


def resolve_root_contract(explicit):
    """Which contract the injected module is built against.

    One build injects exactly one family's module, so the choice must be explicit once
    more than one contract exists; a silent pick would ship a module whose vermagic
    belongs to another kernel.
    """
    if explicit:
        return explicit
    documents = sorted((ROOT / "root-loader" / "contracts").glob("*.json"))
    if len(documents) == 1:
        return str(documents[0])
    preferred = ROOT / "root-loader" / "contracts" / (
        "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k.json")
    if preferred in documents:
        return str(preferred)
    raise SystemExit("several contracts exist; pass --root-contract: " +
                     ", ".join(c.name for c in documents))


def build_root_product(output, ndk, report, contract=None):
    """Build the release-independent root module and record it for packaging.

    This is the Ultra target's kernel payload. Unlike the IR module it needs no
    per-release spec: every symbol is resolved at load time, so one file serves every
    kernel release of a KMI. A failure here is fatal for the same reason the IR build
    is: an APK whose chosen target cannot install its kernel half must not look
    complete.
    """
    log_path = output / "build.log"
    root_dir = output / ROOT_DIR
    # Bring-up knob, same shape as MAPLESU_IR_DEFINES: a bisect build is a
    # command-line decision, not a source edit.
    extra = []
    for item in os.environ.get("MAPLESU_ROOT_DEFINES", "").split(","):
        if item.strip():
            extra += ["--extra-flag=-D" + item.strip()]
    if contract is None:
        contract = resolve_root_contract(None)
    code = _log_command([sys.executable, str(ROOT / "root-loader" / "build_root_module.py"),
                         "--ndk-dir", str(ndk), "--out-dir", str(root_dir),
                         "--contract", contract, "--quiet"] + extra,
                        log_path)
    if code:
        report["root"] = {"error": "root-loader module build failed", "exitCode": code}
        return code
    build = json.loads((root_dir / "build.json").read_text(encoding="utf-8"))
    module = Path(build["modulePath"])
    if not module.is_absolute():
        module = ROOT.parent / module
    if (not module.is_file() or sha(module) != build["sha256"] or
            module.stat().st_size != int(build["bytes"])):
        report["root"] = {"error": "root-loader module identity drift", "module": str(module)}
        return 3
    if not build.get("gateResult", {}).get("ok"):
        report["root"] = {"error": "root-loader module gates failed",
                          "gateResult": build.get("gateResult")}
        return 3
    facts = build.get("facts") or {}
    if facts.get("helperPath") != ROOT_CANARY_HELPER_PATH:
        report["root"] = {
            "error": "helper path drift: the module execs %r but the App stages %r"
                     % (facts.get("helperPath"), ROOT_CANARY_HELPER_PATH),
            "hint": "maplesu_root.c MAPLESU_HELPER_PATH and this constant are one decision"}
        return 3
    report["root"] = {
        "module": {"path": str(module), "sha256": sha(module),
                   "bytes": module.stat().st_size,
                   "kernelRelease": build.get("kernelRelease"),
                   "kmi": kmi_of(build.get("kernelRelease") or ""),
                   "contract": contract,
                   "imports": facts.get("imports") or [],
                   # The id do_one_initcall() compares against, taken from the image's
                   # own compare site rather than from a literal in the source.
                   "kcfiInitTypeId": (((facts.get("kcfi") or {}).get("initCheckSite")
                                       or {}).get("kcfiTypeId")
                                      or facts.get("kcfiInitTypeId")),
                   "helperPath": facts.get("helperPath"),
                   "handoverMode": facts.get("handoverMode"),
                   "gateResult": build.get("gateResult")},
    }
    return 0


def build_root_l3_product(output, ndk, report, contract=None):
    """Keep each measured kernel family's SU gate beside its first-stage payload."""
    contract = Path(contract or resolve_root_contract(None))
    selected = json.loads(contract.read_text(encoding="utf-8"))
    contracts = {kmi_of(selected["kernelRelease"]): contract}
    for candidate in sorted((ROOT / "root-loader/contracts").glob("*.json")):
        body = json.loads(candidate.read_text(encoding="utf-8"))
        family = kmi_of(body.get("kernelRelease", ""))
        # Older bring-up contracts without L3 measurements are not L3 inputs.
        if family and body.get("l3") and family not in contracts:
            contracts[family] = candidate
    modules = {}
    for family, candidate in contracts.items():
        result = {}
        directory = output if candidate == contract else output / L3_DIR / "per-kmi" / family
        directory.mkdir(parents=True, exist_ok=True)
        rc = build_root_l3_single(directory, ndk, result, candidate)
        if rc or result["l3"].get("state") != "BUILT":
            report["l3"] = result["l3"]
            return rc
        modules[family] = result["l3"]["module"]
    report["l3"] = {"state": "BUILT", "module": modules[kmi_of(selected["kernelRelease"])],
                    "modules": modules}
    return 0


def build_root_l3_single(output, ndk, report, contract):
    """Build the L3 su gate the UMH helper loads into the stage-2 slot.

    L3 ABI v1 (2026-10-31): `maplesu_l3.ko` exposes
    `prctl(0x4D535503, op, token_lo, token_hi, owner_uid)` and commits one
    pre-allocated kernel root cred to the calling task on `op=2`. It is a *third*
    product of the same contract the root module is built from, so the two modules
    always agree on vermagic and KCFI ids.

    Fail-closed without blocking today's tree: this branch was written against a
    root-loader that builds `maplesu_root` and `maplesu_l2` only. An absent L3
    builder is recorded as `state=ABSENT` and the build continues - the App then
    stages no L3 module and reports `suGate=UNAVAILABLE`, which is the honest
    state. The moment the builder accepts `--module maplesu_l3`, every failure
    below (build, identity drift, red gates) is fatal, because from then on a
    packaged APK without a gated L3 module would advertise an su that cannot work.
    """
    log_path = output / "build.log"
    l3_dir = output / L3_DIR
    extra = []
    for item in os.environ.get("MAPLESU_ROOT_DEFINES", "").split(","):
        if item.strip():
            extra += ["--extra-flag=-D" + item.strip()]
    if contract is None:
        contract = resolve_root_contract(None)
    command = [sys.executable, str(ROOT / "root-loader" / "build_root_module.py"),
               "--module", L3_MODULE, "--ndk-dir", str(ndk),
               "--out-dir", str(l3_dir), "--contract", str(contract), "--quiet"] + extra
    result = subprocess.run(command, capture_output=True, text=True)
    with log_path.open("a", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        log.write(result.stdout or "")
        log.write(result.stderr or "")
    if result.returncode:
        transcript = (result.stdout or "") + (result.stderr or "")
        if "invalid choice" in transcript and L3_MODULE in transcript:
            report["l3"] = {
                "state": "ABSENT",
                "suGate": "UNAVAILABLE",
                "exitCode": result.returncode,
                "reason": "root-loader/build_root_module.py does not build "
                          + L3_MODULE + " yet; no L3 module is packaged",
            }
            print("MAPLESU L3 WARNING: the root-loader builder has no " + L3_MODULE +
                  " module; packaging will record l3Module=null and the App will report "
                  "suGate=UNAVAILABLE")
            return 0
        # The reason is the last non-empty line of the builder's own output; keeping it
        # here means the report alone explains a red L3 edge (the full transcript stays
        # in build.log).
        detail = next((line.strip() for line in reversed(transcript.splitlines())
                       if line.strip()), "")
        report["l3"] = {"state": "FAILED", "suGate": "UNAVAILABLE",
                        "error": "root-loader L3 module build failed",
                        "detail": detail,
                        "exitCode": result.returncode}
        return 3
    build = json.loads((l3_dir / "build.json").read_text(encoding="utf-8"))
    module = Path(build["modulePath"])
    if not module.is_absolute():
        module = ROOT.parent / module
    if (not module.is_file() or sha(module) != build["sha256"] or
            module.stat().st_size != int(build["bytes"])):
        report["l3"] = {"state": "FAILED", "error": "L3 module identity drift",
                        "module": str(module)}
        return 3
    if not build.get("gateResult", {}).get("ok"):
        report["l3"] = {"state": "FAILED", "error": "L3 module gates failed",
                        "gateResult": build.get("gateResult")}
        return 3
    facts = build.get("facts") or {}
    report["l3"] = {
        "state": "BUILT",
        "module": {"path": str(module), "sha256": sha(module),
                   "bytes": module.stat().st_size,
                   "kernelRelease": build.get("kernelRelease"),
                   "kmi": kmi_of(build.get("kernelRelease") or ""),
                   "contract": str(contract),
                   "gateResult": build.get("gateResult"),
                   "imports": facts.get("imports") or []},
    }
    return 0


def build_root_canary(output, ndk, report):
    """Build the step-3 hook-module set. Returns an exit code (0 = packaged)."""
    log_path = output / "build.log"
    canary_root = output / ROOT_CANARY_DIR
    warnings = []

    code = _log_command([sys.executable, str(ROOT / "root-loader" / "build_root_module.py"),
                         "--ndk-dir", str(ndk), "--out-dir", str(canary_root), "--quiet"],
                        log_path)
    if code:
        report["ir"] = {"error": "root-loader module build failed", "exitCode": code}
        return code
    build = json.loads((canary_root / "build.json").read_text(encoding="utf-8"))
    module = Path(build["modulePath"])
    if not module.is_absolute():
        module = ROOT.parent / module
    if not module.is_file() or sha(module) != build["sha256"] or \
            module.stat().st_size != int(build["bytes"]):
        report["ir"] = {"error": "root-loader module identity drift", "module": str(module)}
        return 3
    if not build.get("gateResult", {}).get("ok"):
        report["ir"] = {"error": "root-loader module gates failed",
                        "gateResult": build.get("gateResult")}
        return 3
    facts = build.get("facts") or {}
    if facts.get("helperPath") != ROOT_CANARY_HELPER_PATH:
        report["ir"] = {
            "error": "helper path drift: the module execs %r but the App stages %r"
                     % (facts.get("helperPath"), ROOT_CANARY_HELPER_PATH),
            "hint": "maplesu_root.c MAPLESU_HELPER_PATH and this constant are the "
                    "same decision (D-5/D3-3); update both together"}
        return 3
    if facts.get("helperPathSource") != "contract":
        warnings.append(
            "the module was verified against a helper path that did not come from the "
            "contract (source=%r); --root-canary packaging always uses the contract"
            % facts.get("helperPathSource"))

    ir_dir = output / "ir"
    ir_dir.mkdir(parents=True, exist_ok=True)
    stub = ir_dir / ROOT_CANARY_STUB_NAME
    stub.write_bytes(ROOT_CANARY_STUB_BYTES)
    if stub.read_bytes()[:4] == b"\x7fELF" or b".ir_spec" in ROOT_CANARY_STUB_BYTES:
        report["ir"] = {"error": "IR stub must not be an ELF image nor name .ir_spec"}
        return 3

    specs = ir_specs(report)
    if specs is None:
        return 3

    imports = facts.get("imports") or []
    # `call_usermodehelper` in the import set is expected and correct: it is the
    # documented fallback. What matters is whether the module *also* carries the
    # two-stage handover, because on this kernel the wrapper alone cannot exec.
    if facts.get("handoverMode") != ROOT_CANARY_HANDOVER:
        warnings.append(
            "the module does not report the two-stage usermodehelper handover "
            "(handoverMode=%r, expected %r); on this kernel "
            "CONFIG_STATIC_USERMODEHELPER_PATH=\"\" rewrites subprocess_info.path to "
            "an empty string, so a wrapper-only module returns 0 without exec'ing "
            "anything and acceptance (c)/(d) would be a false positive"
            % (facts.get("handoverMode"), ROOT_CANARY_HANDOVER))
    umh_primary = facts.get("umhPrimary") or {}
    if umh_primary.get("missing"):
        warnings.append(
            "the module does not resolve %s; the two-stage handover cannot work and "
            "the fallback four-argument call is dead on this image"
            % ", ".join(umh_primary["missing"]))
    raw = module.read_bytes()
    missing = [needle.decode() for needle in ROOT_CANARY_PRINTKS if needle not in raw]
    if missing:
        warnings.append(
            "the module carries no printk for %s; kernel-side evidence for acceptance "
            "(b)/(c) will be missing" % ", ".join(missing))

    report["ir"] = {
        "mode": "root-canary",
        "build": {"module": str(stub), "sha256": sha(stub),
                  "bytes": stub.stat().st_size,
                  "facts": {"kcfiInitTypeId": "", "imports": [], "stub": True}},
        "specs": specs,
        "canary": {"path": str(module), "sha256": sha(module),
                   "bytes": module.stat().st_size,
                   "purpose": "step-3 hook module: the UMH handover target is "
                              "<CE>/bs, which the App stages on every route"},
        "rootCanary": {
            "enabled": True,
            "module": {"path": str(module), "sha256": sha(module),
                       "bytes": module.stat().st_size,
                       "kernelRelease": build.get("kernelRelease"),
                       "modulePath": build.get("modulePath"),
                       "contract": build.get("contract"),
                       "gateResult": build.get("gateResult"),
                       "imports": imports,
                       "helperPath": facts.get("helperPath"),
                       "helperPathSource": facts.get("helperPathSource"),
                       "handoverMode": facts.get("handoverMode"),
                       "umhPrimary": facts.get("umhPrimary"),
                       "umhArgvStorage": facts.get("umhArgvStorage"),
                       "subprocessInfoPathOffset":
                           facts.get("subprocessInfoPathOffset"),
                       "kcfiTraps": facts.get("kcfiTraps")},
            # Step-3 (§3.4): no canaryHelper asset. The module execs the `bs` the App
            # already stages, so shipping a second 5.5 KB helper would reintroduce the
            # two-track helper problem D-5 exists to remove.
            "helper": None,
            "helperPath": ROOT_CANARY_HELPER_PATH,
            "helperAsset": None,
            "stub": {"path": str(stub), "sha256": sha(stub),
                     "bytes": stub.stat().st_size,
                     "reason": "non-ELF; install_ir() fails at the spec-section "
                               "lookup, so the deprecated module is never loaded"},
            "deprecatedModuleBuilt": False,
            "warnings": warnings,
            "notes": [
                "step-3 semantics: the canary slot carries the hook module and the "
                "module execs <CE>/bs (the App-staged bootstrap); append --umh "
                "--entry-enforcing <saved>",
                "no canaryHelper asset is packaged from step-3 on; a stale "
                "maplesu-bootstrap in the attempt directory is removed by "
                "IrImages.stage",
            ],
        },
    }
    for warning in warnings:
        print("MAPLESU CANARY WARNING: " + warning)
    return 0



if __name__ == "__main__":
    sys.path.insert(0, str(ROOT.parent / "tools"))
    import native_toolchain

    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=Path, default=native_toolchain.sdk_dir(),
                        help="Android SDK; default is ANDROID_HOME or ANDROID_SDK_ROOT")
    parser.add_argument("--ndk", default="28.2.13676358")
    parser.add_argument("--ndk-dir", type=Path)
    parser.add_argument("--review", action="store_true")
    parser.add_argument("--ghostlock-adapter", action="store_true")
    parser.add_argument("--dirtyfrag-adapter", action="store_true")
    parser.add_argument("--ko-variant", choices=("minimal", "stock"), default="minimal")
    parser.add_argument("--root-contract", default=None,
                        help="contract for the in-kernel root module. Required once more "
                             "than one contract exists, because one build injects exactly "
                             "one family's module")
    parser.add_argument("--root-canary", action="store_true",
                        help="package the root-loader hook module in the canary slot: "
                             "the product IR slot carries a non-ELF stub and the "
                             "freshly built maplesu_root.ko is delivered through the "
                             "canary probe. From step-3 on the module execs the "
                             "App-staged <CE>/bs with --umh, so no separate canary "
                             "helper asset is packaged")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("set ANDROID_HOME (or ANDROID_SDK_ROOT), or pass --sdk")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    retained = verify()
    ndk = args.ndk_dir or args.sdk / "ndk" / args.ndk
    _exe = '.exe' if os.name == 'nt' else ''
    cmake = args.sdk / f'cmake/3.22.1/bin/cmake{_exe}'
    # Packaged adapters share the Manager minimum; upstream review stays separate.
    platform = "-DANDROID_PLATFORM=android-35" if args.review else "-DANDROID_PLATFORM=android-31"

    targets = ["maplesu-root-consumer"]
    if args.review:
        targets += ["maplesu-ghostlock-upstream-review", "maplesu-dirtyfrag-upstream-review",
                    "maplesu-dirtyfrag-bootstrap-upstream-review"]
    if args.ghostlock_adapter:
        targets += ["maplesu-ghostlock-adapter"]
    if args.dirtyfrag_adapter:
        targets += ["maplesu-dirtyfrag-native", "maplesu-dirtyfrag-bootstrap"]

    # The stager embeds one module image at configure time, so the module has to be
    # built before CMake runs. Doing it here also means a failed module build stops
    # before any native compile.
    #
    # One contract decides every module this build injects (root and L3), so it is
    # resolved once, up front. Before this assignment the variable only existed
    # inside the `--dirtyfrag-adapter` branch below, which made any other build that
    # reached the module step (for example `--review`) fail with a NameError.
    contract = args.root_contract
    root_override = []
    if args.dirtyfrag_adapter and not args.root_canary:
        root_dir = output / ROOT_DIR
        contract = resolve_root_contract(args.root_contract)
        # One module per contract we have, so a single APK carries OUR module for every
        # family it can instead of one family plus foreign builds.
        per_kmi = root_dir / "per-kmi"
        per_kmi.mkdir(parents=True, exist_ok=True)
        built_families = []
        for candidate in sorted((ROOT / "root-loader" / "contracts").glob("*.json")):
            body = json.loads(candidate.read_text(encoding="utf-8"))
            kmi = kmi_of(body.get("kernelRelease") or "")
            if kmi is None:
                continue
            command = [sys.executable,
                       str(ROOT / "root-loader" / "build_root_module.py"),
                       "--ndk-dir", str(ndk), "--out-dir", str(per_kmi / kmi),
                       "--contract", str(candidate), "--quiet"]
            image = body.get("imagePath")
            if image and (ROOT / image).is_file():
                command += ["--image", str(ROOT / image)]
            if subprocess.run(command).returncode:
                print("MAPLESU: module build failed for %s; keeping its stock payload"
                      % kmi)
                continue
            module = per_kmi / kmi / (body["module"] + ".ko")
            if module.is_file():
                shutil.copy2(module, per_kmi / (kmi + ".ko"))
                built_families.append(kmi)
        print("MAPLESU: per-KMI modules for " + (", ".join(built_families) or "none"))

        rc = subprocess.run([sys.executable, str(ROOT / "root-loader" / "build_root_module.py"),
                             "--ndk-dir", str(ndk), "--out-dir", str(root_dir),
                             "--contract", contract, "--quiet"]).returncode
        if rc:
            raise SystemExit(rc)
        release = json.loads((root_dir / "build.json").read_text(encoding="utf-8")).get(
            "kernelRelease", "")
        if kmi_of(release) is None:
            raise SystemExit("build_root_product: unsupported kernel release " + repr(release))
        root_override = [
            "-DMAPLESU_KO_OVERRIDE=" + (root_dir / "maplesu_root.ko").as_posix(),
            "-DMAPLESU_KO_OVERRIDE_KMI=" + (kmi_of(release) or ""),
        ]
        if built_families:
            root_override.append("-DMAPLESU_KO_OVERRIDE_DIR=" + per_kmi.as_posix())

    commands = [
        [str(cmake), "-S", str(ROOT), "-B", str(output / "build"), "-G", "Ninja",
         f"-DCMAKE_MAKE_PROGRAM={cmake.with_name(f'ninja{_exe}')}",
         f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
         "-DANDROID_ABI=arm64-v8a", platform,
         f"-DMAPLESU_BUILD_UPSTREAM_REVIEW={'ON' if args.review else 'OFF'}",
         f"-DMAPLESU_BUILD_GHOSTLOCK_ADAPTER={'ON' if args.ghostlock_adapter else 'OFF'}",
         f"-DMAPLESU_BUILD_DIRTYFRAG_ADAPTER={'ON' if args.dirtyfrag_adapter else 'OFF'}",
         f"-DMAPLESU_DIRTYFRAG_KO_VARIANT={args.ko_variant}",
         "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release"] + root_override,
        [str(cmake), "--build", str(output / "build"), "--target", *targets, "--parallel", "4"],
    ]
    if args.ghostlock_adapter:
        commands.append([sys.executable, str(ROOT / "ghostlock/build_extractor.py"),
                         "--ndk", str(ndk), "--output", str(output / "extractor")])
    before = inputs()
    report = {
        "startedAtUtc": datetime.now(timezone.utc).isoformat(),
        "commands": commands,
        "inputsBefore": before,
        "targets": targets,
        "koVariant": args.ko_variant,
        "deviceExecuted": False,
        "managerPackaged": False,
        "restorationVerified": False,
        "daemonHandoffVerified": False,
        "retainedSources": retained,
        "reviewBuild": args.review,
        "ghostlockAdapterBuild": args.ghostlock_adapter,
        "dirtyfragAdapterBuild": args.dirtyfrag_adapter,
    }
    exit_code = 0
    with (output / "build.log").open("w", encoding="utf-8") as log:
        for command in commands:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            if process.returncode:
                exit_code = process.returncode
                break
    report["exitCode"] = exit_code
    report["inputsAfter"] = inputs()
    report["inputsStable"] = report["inputsAfter"] == before
    report["artifacts"] = products(output) if exit_code == 0 else []
    if exit_code == 0 and args.dirtyfrag_adapter:
        report["dirtyfragModules"] = dirtyfrag_modules(output)
    overlay = output / "build/dirtyfrag/maplesu-overlay/overlay.json"
    if overlay.is_file():
        report["dirtyfragOverlay"] = {"path": str(overlay), "sha256": sha(overlay),
                                      "content": json.loads(overlay.read_text(encoding="utf-8"))}
    # In-kernel root module (manual W1). Built here, in the same edge that produces
    # the providers, so the APK can never ship a module that no build verified.
    # A failure is fatal on purpose: a jailbreak that cannot install its kernel
    # root must not look packaged and complete.
    if exit_code == 0:
        ir_dir = output / "ir"
        if args.root_canary:
            # Step 2 canary: rebuilt from source here, never copied from an
            # earlier experimental build.
            exit_code = build_root_canary(output, ndk, report)
        else:
            builder = ROOT / "ir-loader" / "build_ir_module.py"
            # Bring-up knob: MAPLESU_IR_DEFINES=IR_STOP_AFTER_VERIFY builds a module that
            # verifies and returns without patching, so init always returns and its own
            # printk survives in the kernel ring buffer for the next attempt to read.
            extra = []
            for item in os.environ.get("MAPLESU_IR_DEFINES", "").split(","):
                if item.strip():
                    extra += ["--define", item.strip()]
            result = subprocess.run([sys.executable, str(builder), "--ndk-dir", str(ndk),
                                     "--out", str(ir_dir)] + extra,
                                    capture_output=True, text=True)
            with (output / "build.log").open("a", encoding="utf-8") as log:
                log.write(result.stdout)
                log.write(result.stderr)
            canary = subprocess.run([sys.executable, str(builder), "--canary",
                                     "--ndk-dir", str(ndk), "--out", str(ir_dir)],
                                    capture_output=True, text=True)
            with (output / "build.log").open("a", encoding="utf-8") as log:
                log.write(canary.stdout)
                log.write(canary.stderr)
            if result.returncode != 0 or canary.returncode != 0:
                exit_code = result.returncode or canary.returncode
                report["ir"] = {"error": "ir-loader build failed", "exitCode": exit_code}
            else:
                ir_build = json.loads((ir_dir / "build.json").read_text(encoding="utf-8"))
                specs = ir_specs(report)
                if specs is None:
                    exit_code = 3
                else:
                    canary_path = ir_dir / "maplesu_ir-canary.ko"
                    report["ir"] = {"build": ir_build, "specs": specs,
                                    "canary": {"path": str(canary_path),
                                               "sha256": sha(canary_path),
                                               "bytes": canary_path.stat().st_size}}
                    # Ultra's payload, packaged on every product build.
                    exit_code = build_root_product(output, ndk, report, contract)
        # L3 su gate (ABI v1). Built last so a red earlier edge never spends time on
        # it; it is recorded in `report["l3"]` for every outcome, never omitted, so a
        # report or an APK can always state which su gate it carries.
        if exit_code == 0:
            exit_code = build_root_l3_product(output, ndk, report, contract)
    report["exitCode"] = exit_code if exit_code else 0 if report["inputsStable"] else 2
    report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
    (output / "build.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("exitCode", "inputsStable", "deviceExecuted",
                                                   "managerPackaged", "targets")}))
    raise SystemExit(exit_code if exit_code else 0 if report["inputsStable"] else 2)
