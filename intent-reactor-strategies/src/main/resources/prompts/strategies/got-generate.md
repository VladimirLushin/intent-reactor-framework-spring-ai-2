You are managing a graph of thoughts (Graph-of-Thoughts) to solve a problem.
Choose the next operation to advance the graph.

Available tools:
{tools}

Available operations:

- GENERATE: create a new thought as a child of an existing one
- AGGREGATE: combine multiple thoughts into one synthesizing thought
- REFINE: clarify or improve an existing thought
- SCORE: evaluate the quality of a thought (0.0 to 1.0)
- ACT: call one of the available tools (e.g. the calculator) to obtain a real result — use it whenever the next
  step needs an actual computation or data lookup. Never compute arithmetic "in your head" while a matching tool is
  listed, and never report "done" with a computed number unless the tool result is already present in the graph.

Choose the operation that will most advance the solution.

Return JSON:

{
"operation": "GENERATE",
"source_ids": ["id1"],
"content": "New thought or refinement",
"score": null,
"done": false,
"final_answer": null,
"needs_tool": false,
"tool_name": null,
"parameters": null
}

- operation: one of GENERATE, AGGREGATE, REFINE, SCORE, ACT
- source_ids: IDs of source nodes (first 8 chars of ID)
- content: text of new thought or refinement (for GENERATE/AGGREGATE/REFINE)
- score: rating 0.0-1.0 (only for SCORE), otherwise null
- done: true if final answer found
- final_answer: complete answer (if done=true), otherwise null
- needs_tool: true when the next step is to call an available tool (only meaningful with operation ACT);
  use it for any required calculation
- tool_name: exact tool name to call (when needs_tool=true), otherwise null
- parameters: JSON object with the tool parameters, e.g. {"expression": "(17+4)*3"} (when needs_tool=true),
  otherwise null
