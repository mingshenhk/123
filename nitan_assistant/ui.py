"""Small UIAutomator XML parser; intentionally does not interpret screenshots."""

from __future__ import annotations

from dataclasses import dataclass
import re
import xml.etree.ElementTree as ET


_BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


@dataclass(frozen=True)
class UiNode:
    text: str
    content_desc: str
    resource_id: str
    class_name: str
    clickable: bool
    bounds: tuple[int, int, int, int] | None
    clickable_ancestor_bounds: tuple[int, int, int, int] | None = None
    package: str = ""
    enabled: bool = True

    @property
    def label(self) -> str:
        return self.text or self.content_desc

    @property
    def visible(self) -> bool:
        return self.bounds is not None and self.bounds[2] > self.bounds[0] and self.bounds[3] > self.bounds[1]

    @property
    def tap_bounds(self) -> tuple[int, int, int, int] | None:
        if self.clickable:
            return self.bounds
        return self.clickable_ancestor_bounds or self.bounds

    @property
    def center(self) -> tuple[int, int] | None:
        bounds = self.tap_bounds
        if not bounds:
            return None
        left, top, right, bottom = bounds
        if right <= left or bottom <= top:
            return None
        return ((left + right) // 2, (top + bottom) // 2)


def _bounds(value: str) -> tuple[int, int, int, int] | None:
    match = _BOUNDS_RE.fullmatch(value)
    return tuple(map(int, match.groups())) if match else None


def parse_hierarchy(xml_text: str) -> list[UiNode]:
    """Parse nodes and remember the closest clickable ancestor's bounds."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise ValueError(f"Invalid UI hierarchy: {exc}") from exc
    nodes: list[UiNode] = []

    def visit(element: ET.Element, clickable_bounds: tuple[int, int, int, int] | None,
              ancestor_package: str) -> None:
        own_bounds = _bounds(element.attrib.get("bounds", ""))
        is_clickable = element.attrib.get("clickable", "false").lower() == "true"
        package = element.attrib.get("package", "")
        enabled = element.attrib.get("enabled", "true").lower() == "true"
        if package != ancestor_package:
            clickable_bounds = None
        nearest_clickable = clickable_bounds
        if is_clickable:
            nearest_clickable = own_bounds if enabled else None
        node = UiNode(
            text=element.attrib.get("text", "").strip(),
            content_desc=element.attrib.get("content-desc", "").strip(),
            resource_id=element.attrib.get("resource-id", "").strip(),
            class_name=element.attrib.get("class", "").strip(),
            clickable=is_clickable,
            bounds=own_bounds,
            clickable_ancestor_bounds=clickable_bounds if not is_clickable else None,
            package=package,
            enabled=enabled,
        )
        if node.text or node.content_desc or node.resource_id:
            nodes.append(node)
        for child in element:
            visit(child, nearest_clickable, package)

    visit(root, None, "")
    return nodes


def node_matches(node: UiNode, *, text: str | None = None, desc: str | None = None,
                 resource_id: str | None = None) -> bool:
    """Exact, case-sensitive match. Callers must supply exactly one selector."""
    selectors = (text, desc, resource_id)
    if sum(value is not None for value in selectors) != 1:
        raise ValueError("provide exactly one of text, desc, or resource_id")
    if text is not None:
        return node.text == text
    if desc is not None:
        return node.content_desc == desc
    return node.resource_id == resource_id
