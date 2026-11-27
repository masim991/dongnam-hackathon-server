import test from "node:test";
import assert from "node:assert/strict";
import { cleanWays, lengthM } from "../scripts/topology-lib.mjs";

const lonScale = 111320 * Math.cos(35.16 * Math.PI / 180);
const point = ([x, y]) => [129.15 + x / lonScale, 35.16 + y / 111320];
const way = (id, points, tags = {}) => ({ id, tags, line: points.map(point) });

test("short gaps are bridged, while gaps beyond the limit remain disconnected", () => {
  const first = way(1, [[0, 0], [100, 0]]);
  const short = cleanWays([first, way(2, [[120, 0], [220, 0]])]);
  assert.equal(short.bridges.length, 1);
  assert.ok(Math.abs(lengthM([short.bridges[0].from, short.bridges[0].to]) - 20) < 0.01);
  assert.equal(cleanWays([first, way(2, [[140, 0], [240, 0]])]).bridges.length, 0);
  assert.deepEqual(cleanWays([]), { ways: [], bridges: [], issues: [] });
});

test("roundabout trimming cannot produce a bridge beyond the configured gap limit", () => {
  const result = cleanWays([
    way(10, [[20, 0], [0, 20], [-20, 0], [0, -20], [20, 0]], { junction: "roundabout" }),
    way(11, [[20, 0], [60, 0]]),
    way(12, [[-20, 0], [-60, 0]]),
    way(13, [[0, -25], [0, -65]]),
  ]);
  assert.ok(result.issues.some((issue) => issue.status === "arc_replaced"));
  assert.equal(result.bridges.length, 0);
  assert.ok(result.issues.some((issue) => issue.type === "gap" && issue.status === "flagged" && issue.meters > 30));
});
