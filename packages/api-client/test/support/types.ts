// Compile-time assertions. `expectType<Equal<A, B>>()` fails to compile unless A and B are exactly the same type,
// and `any` is never Equal to anything else — so a degraded `any` fails too.
export type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2 ? true : false;
export type IsAny<T> = 0 extends 1 & T ? true : false;

export function expectType<T extends true>(): T | void {}
