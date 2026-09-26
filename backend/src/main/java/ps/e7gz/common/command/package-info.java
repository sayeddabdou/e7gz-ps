/**
 * {@code CommandRunner}: one READ COMMITTED transaction per business write, begin_command with the lock set, retry on 40001 (arch 04 §2.1).
 * <p>Filled by: E7-15.
 */
package ps.e7gz.common.command;
