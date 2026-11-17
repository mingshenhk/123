"""Safety checks shared by direct and JSON-driven actions."""

from __future__ import annotations

from collections.abc import Callable

HUMAN_CHECK_TERMS = (
    "捣蛋鬼",
    "验证码",
    "人工验证",
    "人机验证",
    "安全验证",
    "安全检测",
    "风险验证",
    "异常访问",
)

RISKY_ACTION_TERMS = (
    "充值",
    "购买",
    "支付",
    "交易",
    "出售",
    "卖出",
    "丢弃",
    "删除",
    "重置",
    "兑换",
    "消耗",
    "扣除",
)

ACCOUNT_ACTION_TERMS = ("登录", "登入", "登陆", "注册", "创建角色", "识别码", "进入游戏",
                        "log in", "sign in", "sign up", "register")


def matching_terms(text: str, terms: tuple[str, ...]) -> tuple[str, ...]:
    lowered = text.casefold()
    return tuple(term for term in terms if term.casefold() in lowered)


def human_check_terms(text: str) -> tuple[str, ...]:
    return matching_terms(text, HUMAN_CHECK_TERMS)


def risky_action_terms(text: str) -> tuple[str, ...]:
    return matching_terms(text, RISKY_ACTION_TERMS)


def blocked_control(text: str) -> bool:
    return bool(risky_action_terms(text) or matching_terms(text, ACCOUNT_ACTION_TERMS))


def wait_for_manual_verification(
    text: str,
    reread_text: Callable[[], str],
    *,
    prompt: Callable[[str], str] = input,
    logger: Callable[[str], None] = print,
) -> str:
    """Pause for a person to resolve a visible check, then verify it cleared."""
    while human_check_terms(text):
        logger("Human verification detected. Automation is paused; complete it on the device.")
        try:
            prompt("Press Enter after the human check is complete (Ctrl+C to stop): ")
        except EOFError as exc:
            raise ValueError("Human verification remains; no interactive input is available") from exc
        text = reread_text()
        if human_check_terms(text):
            logger("Verification is still visible; automation remains paused.")
    return text
