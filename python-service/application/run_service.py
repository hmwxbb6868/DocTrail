from __future__ import annotations

from copy import deepcopy
from threading import RLock
from uuid import UUID

from agent.state import AgentState

#同一个 `run_id`，被用来绑定另一组不同 `request_id / trace_id`，说明上游调用错乱，链路会串掉
class AgentRunIdentityConflictError(RuntimeError):
    pass


class AgentRunService:
    """Keeps single-process AgentState snapshots for internal diagnostics only."""

    def __init__(self) -> None:
        self._lock = RLock()#创建一把可重入锁 RLock
        self._states: dict[UUID, AgentState] = {}#创建一个空字典，用于存储 AgentState 对象

    def save(self, state: AgentState) -> None:
        snapshot = deepcopy(state) #对 state 做完整深拷贝，生成一份独立副本，赋值给 snapshot
        #自动获取锁，代码块执行结束**自动释放锁**，哪怕里面抛异常也会释放，不会死锁
        with self._lock:
            #拿【传进来的 state 的 run_id】，去查【AgentRunService 自己的内存字典】，看看这个 run_id 之前有没有存档。
            existing = self._states.get(state.run_id)
            #run_id 重复了，但不属于同一条链路
            if existing is not None and (
                existing.request_id != state.request_id
                or existing.trace_id != state.trace_id
            ):
                raise AgentRunIdentityConflictError(
                    "run_id is already associated with another request"
                )
            #如果 run_id 已经存在、并且是同一条链路，就用新的 snapshot 覆盖原来的 value（AgentStatus对象）
            self._states[state.run_id] = snapshot

    def find(self, run_id: UUID) -> AgentState | None:
        with self._lock:
            state = self._states.get(run_id)
            return deepcopy(state) if state is not None else None
