"""Deterministic diagnostic scoring for the ingredient benchmark.

parse_reply accepts the app's bullet/numbered-list formats or NONE, and raises
ValueError for empty/off-script output. It retains duplicate list lines so they
can be measured; the app itself removes identical strings after parsing.

score_case matches normalized *whole top-level names*, never substrings. Case,
accents, punctuation, whitespace, and organic asterisks are ignored. Parenthetic
content is excluded from the main name match; its recall is reported separately.
Consequently a high top-level F1 alone does not establish composition accuracy.
French/English aliases map to one canonical item and cannot multiply recall.

Optional items are permitted but excluded from both precision and recall
denominators. Duplicates also do not change these denominators, and are counted
separately. Unknown items are false positives. Empty negative cases score 1 for
precision/recall/F1; a nonempty negative has precision/F1 0 and recall 1 by the
empty-reference convention. false_none means an empty output on a positive
case, including an empty programmatic baseline, not necessarily a literal NONE.

Subingredient matching requires the child to be returned inside its matched
parent's brackets. Expected alias sublists of the same length provide positional
translation aliases for those children. Repeated parent outputs may jointly
recover children, but still count as duplicate parents. Child recall includes
children of missing parents in its denominator. Order checks consider only the
relative order of the first occurrences of returned required parents.

The scorer receives parsed items, so output-format success is the caller's
responsibility. It also accepts programmatic ingredient lists, e.g. the rule
baseline. This is a transparent small-case comparison, not a safety metric.
"""

from __future__ import annotations

import argparse
import json
import re
import unicodedata
from collections import defaultdict
from typing import Any


_LIST_ITEM = re.compile(r"^\s*(?:[-*•·]|\d{1,3}[.)])\s+(.+)$")


def _clean_item(item: str) -> str:
    return re.sub(
        r"^[\s\"'*_]+|[\s\"'*_,;.]+$", "", item.replace("**", "").replace("`", "")
    ).strip()


def parse_reply(reply: str | None) -> list[str]:
    """Parse supported list lines or NONE; retain duplicates for diagnostics."""
    if reply is None or not reply.strip():
        raise ValueError("The model returned an empty reply")
    items: list[str] = []
    for line in reply.splitlines():
        match = _LIST_ITEM.match(line)
        if match:
            item = _clean_item(match.group(1))
            if item and item.upper() != "NONE":
                items.append(item)
    if items:
        return items
    normalized = re.sub(r"[^A-Za-z]+", " ", reply).strip().upper()
    if normalized == "NONE" or normalized.endswith(" NONE"):
        return []
    raise ValueError("The model's reply wasn't an ingredient list")


def normalize(text: str) -> str:
    """Normalize text without stemming, fuzzy recovery, or substring matching."""
    decomposed = unicodedata.normalize("NFKD", text).casefold()
    unaccented = "".join(c for c in decomposed if not unicodedata.combining(c))
    # Asterisks mark organic ingredients on the real label, not word boundaries.
    unaccented = unaccented.replace("*", "")
    return " ".join("".join(c if c.isalnum() else " " for c in unaccented).split())


def _top_name(item: str) -> str:
    return re.split(r"[\[(]", item, maxsplit=1)[0].strip()


def _split_top_level(text: str) -> list[str]:
    parts: list[str] = []
    current: list[str] = []
    depth = 0
    for char in text:
        if char in "([":
            depth += 1
        elif char in ")]":
            depth = max(0, depth - 1)
        if depth == 0 and char in ",;•·":
            part = "".join(current).strip()
            if part:
                parts.append(part)
            current = []
        else:
            current.append(char)
    part = "".join(current).strip()
    if part:
        parts.append(part)
    return parts


def _children(item: str) -> list[str]:
    """Flatten bracketed descendants, retaining their parent names and order."""
    children: list[str] = []
    start: int | None = None
    depth = 0
    for index, char in enumerate(item):
        if char in "([":
            if depth == 0:
                start = index + 1
            depth += 1
        elif char in ")]" and depth > 0:
            depth -= 1
            if depth == 0 and start is not None:
                for part in _split_top_level(item[start:index]):
                    name = _top_name(part)
                    if normalize(name):
                        children.append(name)
                    children.extend(_children(part))
                start = None
    if depth and start is not None:  # A dropped closing bracket is common OCR noise.
        for part in _split_top_level(item[start:]):
            name = _top_name(part)
            if normalize(name):
                children.append(name)
            children.extend(_children(part))
    return children


def _unique(items: list[str]) -> list[str]:
    return list(dict.fromkeys(items))


def score_case(case: dict[str, Any], items: list[str]) -> dict[str, Any]:
    """Score parsed model output or a programmatically produced baseline list."""
    required = _unique(case.get("expected_ingredients", []))
    optional = [item for item in _unique(case.get("optional_ingredients", [])) if item not in required]
    aliases = case.get("expected_aliases", {})
    canonical_items = required + optional
    name_to_canonical: dict[str, str] = {}
    for canonical in canonical_items:
        for variant in [canonical, *aliases.get(canonical, [])]:
            name = normalize(_top_name(variant))
            if not name:
                continue
            previous = name_to_canonical.get(name)
            if previous is not None and previous != canonical:
                raise ValueError(f"Ambiguous alias {variant!r}: {previous!r} / {canonical!r}")
            name_to_canonical[name] = canonical

    found: dict[str, list[str]] = defaultdict(list)
    unknown: dict[str, str] = {}
    seen_semantic: set[tuple[str, str]] = set()
    duplicate_count = 0
    returned_required_order: list[str] = []
    for raw_item in items:
        if not isinstance(raw_item, str):
            raise TypeError("Every ingredient item must be a string")
        name = normalize(_top_name(raw_item))
        canonical = name_to_canonical.get(name)
        # Empty/malformed programmatic entries remain visible false positives.
        semantic_key = ("canonical", canonical) if canonical is not None else ("unknown", name)
        if semantic_key in seen_semantic:
            duplicate_count += 1
        else:
            seen_semantic.add(semantic_key)
            if canonical in required:
                returned_required_order.append(canonical)
        if canonical is not None:
            found[canonical].append(raw_item)
        else:
            unknown.setdefault(name, raw_item)

    matched = [item for item in required if item in found]
    missing = [item for item in required if item not in found]
    optional_found = [item for item in optional if item in found]
    unexpected = list(unknown.values())
    recall = len(matched) / len(required) if required else 1.0
    precision_denominator = len(matched) + len(unexpected)
    precision = len(matched) / precision_denominator if precision_denominator else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0

    child_total = 0
    matched_children = 0
    missing_children: list[dict[str, Any]] = []
    unexpected_children: list[dict[str, Any]] = []
    for parent in required:
        expected_children = _children(parent)
        returned_children = _unique([
            normalize(child)
            for returned_parent in found.get(parent, [])
            for child in _children(returned_parent)
        ])
        if not expected_children:
            if returned_children:
                unexpected_children.append({"parent": parent, "unexpected": returned_children})
            continue
        child_aliases = [{normalize(child)} for child in expected_children]
        for variant in aliases.get(parent, []):
            translated = _children(variant)
            if len(translated) == len(expected_children):
                for child_set, alternate in zip(child_aliases, translated):
                    child_set.add(normalize(alternate))
        missing_for_parent: list[str] = []
        for expected_child, acceptable in zip(expected_children, child_aliases):
            child_total += 1
            if any(child in acceptable for child in returned_children):
                matched_children += 1
            else:
                missing_for_parent.append(expected_child)
        if missing_for_parent:
            missing_children.append({"parent": parent, "missing": missing_for_parent})
        allowed_children = set().union(*child_aliases)
        extras = [child for child in returned_children if child not in allowed_children]
        if extras:
            unexpected_children.append({"parent": parent, "unexpected": extras})

    expected_returned_order = [item for item in required if item in found]
    expected_none = bool(case.get("expected_none", not required))
    return {
        "required_total": len(required),
        "matched_required": len(matched),
        "matched": matched,
        "recall": recall,
        "precision": precision,
        "f1": f1,
        "missing": missing,
        "unexpected": unexpected,
        "optional_found": optional_found,
        "duplicate_count": duplicate_count,
        "false_none": bool(required and not items),
        "negative_correct": not items if expected_none else None,
        "required_subingredient_total": child_total,
        "matched_required_subingredients": matched_children,
        "subingredient_recall": matched_children / child_total if child_total else None,
        "missing_subingredients": missing_children,
        "unexpected_subingredients": unexpected_children,
        "relative_order_correct": returned_required_order == expected_returned_order,
    }


def _self_check() -> int:
    assert parse_reply("Here are the ingredients:\n- sugar\n1. salt") == ["sugar", "salt"]
    assert parse_reply("**NONE**") == []
    assert parse_reply("- NONE") == []
    assert parse_reply("- sugar\n- sugar") == ["sugar", "sugar"]
    for malformed in ("", None, "The ingredients are sugar and salt."):
        try:
            parse_reply(malformed)
        except ValueError:
            pass
        else:
            raise AssertionError("Malformed reply accepted")
    basic = {"expected_ingredients": ["Cocoa", "Cocoa butter"], "optional_ingredients": ["Salt"]}
    score = score_case(basic, ["cocoa butter", "salt", "milk"])
    assert score["matched_required"] == 1 and score["missing"] == ["Cocoa"]
    assert score["precision"] == 0.5 and score["optional_found"] == ["Salt"]
    assert score["unexpected"] == ["milk"]
    bilingual = {"expected_ingredients": ["Soy lecithin"], "expected_aliases": {"Soy lecithin": ["Lécithine de soja"]}}
    score = score_case(bilingual, ["LÉCITHINE de soja*", "soy lecithin"])
    assert score["recall"] == 1 and score["duplicate_count"] == 1 and not score["unexpected"]
    nested = {"expected_ingredients": ["Chocolate (sugar, cocoa butter, soy lecithin)"]}
    score = score_case(nested, ["Chocolate"])
    assert score["f1"] == 1 and score["subingredient_recall"] == 0
    assert score_case(nested, ["Chocolate (sugar)"])["matched_required_subingredients"] == 1
    assert score_case(nested, ["Chocolate (sugar, cocoa butter, soy lecithin)"])["subingredient_recall"] == 1
    negative = {"expected_ingredients": [], "expected_none": True}
    assert score_case(negative, [])["negative_correct"] is True
    assert score_case(negative, ["peanuts"])["f1"] == 0
    assert score_case(basic, [])["false_none"] is True
    assert score_case(basic, ["Cocoa butter", "Cocoa"])["relative_order_correct"] is False
    assert score_case(basic, ["Cocoa (milk)"])["unexpected_subingredients"] == [{"parent": "Cocoa", "unexpected": ["milk"]}]
    return 18


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true", help="Run deterministic scoring checks")
    args = parser.parse_args()
    if not args.self_check:
        parser.error("Use --self-check, or import parse_reply and score_case")
    print(json.dumps({"self_check": "passed", "checks": _self_check()}))
