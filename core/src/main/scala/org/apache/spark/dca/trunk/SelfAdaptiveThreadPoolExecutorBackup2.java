package org.apache.spark.dca.trunk;
//package org.apache.spark.dca;
//
//import java.io.BufferedReader;
//import java.io.FileInputStream;
//import java.io.FileNotFoundException;
//import java.io.IOException;
//import java.io.InputStreamReader;
//import java.io.RandomAccessFile;
//import java.lang.management.ManagementFactory;
//import java.lang.management.ThreadInfo;
//import java.lang.management.ThreadMXBean;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map.Entry;
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.ConcurrentSkipListMap;
//import java.util.concurrent.ThreadFactory;
//import java.util.concurrent.ThreadPoolExecutor;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.AtomicLong;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//import org.apache.spark.util.Utils;
//
//import scala.Enumeration.Val;
//
//import org.apache.spark.executor.Executor.TaskRunner;
//import org.apache.commons.lang3.tuple.Pair;
//import org.apache.log4j.Logger;
//
//
//// The problem is that in epoll_16, the tasks which finish have been partially executed and that skews time.
//
//
//public class SelfAdaptiveThreadPoolExecutorBackup2 extends ThreadPoolExecutor {
//
//	private final static Logger log = Logger.getLogger("SelfAdaptiveThreadPoolExecutor");
//	private AtomicInteger submittedTasksNum = new AtomicInteger(0);
//	private AtomicInteger initialMaximumPoolSize = new AtomicInteger(0);
//	private AtomicBoolean isMinReached = new AtomicBoolean(false);
//	private AtomicInteger currentLineNumber = new AtomicInteger(0);
//	private AtomicInteger currentLineNumberIoStat = new AtomicInteger(0);
//	private AtomicInteger finishedTasksNum = new AtomicInteger(0);
//	private AtomicLong straceStartTime = new AtomicLong(0);
//	private AtomicBoolean isTuning = new AtomicBoolean(false);
//	private AtomicBoolean tuningFinished = new AtomicBoolean(false);
//	private ArrayList<Long> throughputs = new ArrayList<Long>();
//	private AtomicLong totalDataRead = new AtomicLong(0);
//	private ConcurrentSkipListMap<Integer, ArrayList<Float>> epolls = new ConcurrentSkipListMap<Integer, ArrayList<Float>>();
//	private AtomicInteger currentStage = new AtomicInteger(0);
//	private String straceFilePath = "";
//	private String ioStatFilePath = "";
//	// private ConcurrentSkipListMap<Integer, Float> epolls_normal_by_time = new
//	// ConcurrentSkipListMap<Integer, Float>();
//
//	public SelfAdaptiveThreadPoolExecutorBackup2(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
//			BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
//		super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
//		// TODO Auto-generated constructor stub
//		initialMaximumPoolSize.set(maximumPoolSize);
//		straceStartTime.set(System.currentTimeMillis());
//	}
//
//	public List<String> readFileInListFromTo(String fileName, int from) {
//
//		List<String> lines = readFileInList(fileName);
//		int to = lines.size();
//		log.debug(String.format("Reading from lines (%s) to (%s)", from, to));
//		if (to < from) {
//
//			log.debug(String.format("For some reason, to is less than from:  (%s) to (%s)", from, to));
//			return null;
//		}
//
//		return lines.subList(from, to);
//	}
//
//	public static List<String> readFileInList(String fileName) {
//
//		List<String> lines = Collections.emptyList();
//		try {
//			lines = Files.readAllLines(Paths.get(fileName), StandardCharsets.UTF_8);
//		}
//
//		catch (IOException e) {
//			e.printStackTrace();
//		}
//		return lines;
//	}
//
//	@Override
//	protected void beforeExecute(Thread t, Runnable r) {
//		// TODO Auto-generated method stub
//
//		if (r instanceof TaskRunner && !tuningFinished.get()) {
//
//			submittedTasksNum.getAndIncrement();
//			TaskRunner taskRunner = (TaskRunner) r;
//			log.debug(String.format("We are going to execute task %s (i.e., beforeExecute())", taskRunner.taskId()));
//
//		}
//
//		super.beforeExecute(t, r);
//	}
//
//	@Override
//	protected void afterExecute(Runnable r, Throwable t) {
//		// log.debug("Threadpool: afterExecute!");
//		log.debug(String.format("Current running threads: %s, coreSize: %s, maxSize: %s", getActiveCount(),
//				getCorePoolSize(), getMaximumPoolSize()));
//		if (r instanceof TaskRunner && !tuningFinished.get()) {
//			finishedTasksNum.getAndIncrement();
//			TaskRunner taskRunner = (TaskRunner) r;
//			log.debug(String.format("Finished executing task %s (i.e., afterExecute())", taskRunner.taskId()));
//
//			log.debug(String.format("We have finished executing %s tasks and submitted %s tasks, we are tuning at %s.",
//					finishedTasksNum.get(), submittedTasksNum.get(), getMaximumPoolSize()));
//
//			Long bytesRead = 0L;
//			// boolean checkTuning = isTuning.get();
//			synchronized (this) {
//
//				try {
//					bytesRead = taskRunner.getMetrics().inputMetrics().bytesRead();
//					totalDataRead.getAndAdd(bytesRead);
//					// ms
//					Long executorRunTime = taskRunner.getMetrics().executorRunTime();
//					Long throughput = bytesRead / (executorRunTime / 1000); // byte per second
//					throughput = (throughput / 1024); // kb per second
//					throughput = (throughput / 1024); // mb per second
//					log.debug(String.format("bytesRead = %s B, executorRunTime = %s ms, throughput = %s MBps", bytesRead,
//							executorRunTime, throughput));
//					throughputs.add(throughput);
//				} catch (Exception e) {
//					// TODO: handle exception
//				}
//
//				if (finishedTasksNum.get() == 1 && getActiveCount() == 1) {
//					log.debug(String.format("Special case where the first task is finished much earlier than others",
//							taskRunner.taskId()));
//					finishedTasksNum.getAndDecrement();
//					super.afterExecute(r, t);
//					return;
//				}
//
//				if (getMaximumPoolSize() == initialMaximumPoolSize.get()) {
//					if (finishedTasksNum.get() == 1) {
//						tune(r);
//					}
//
//					super.afterExecute(r, t);
//					return;
//				}
//
//				// If we are running num tasks equal to maximum pool size, start monitoring
//				// strace.
//				// - 1 because the finished task is also included in the active count.
//				int activeTaskCount = getActiveCount() - 1;
//				log.debug(String.format("We are running %s tasks and checking if its equal to maxPoolSize: %s",
//						activeTaskCount, getMaximumPoolSize()));
//				if (activeTaskCount == getMaximumPoolSize()) {
//					String straceFilePath = taskRunner.getStracePath();
//					String ioFilePath = taskRunner.getIoStatPath();
//					currentLineNumber.set(readFileInList(straceFilePath).size());
//					currentLineNumberIoStat.set(readFileInList(ioFilePath).size());
//					log.debug(String.format("Starting monitoring strace at line: %s", currentLineNumber));
//					log.debug(String.format("Starting monitoring ioStat at line: %s", currentLineNumberIoStat));
//					// Reset the number of submitted tasks for next iteration.
//					submittedTasksNum.set(0);
//					finishedTasksNum.set(0);
//					Long now = System.currentTimeMillis();
//					straceStartTime.set(now);
//					log.debug(String.format("Starting monitoring time: %s", now));
//					totalDataRead.set(0);
//					super.afterExecute(r, t);
//					return;
//				}
//
//				if (finishedTasksNum.get() == getMaximumPoolSize()) {
//
//					// if (submittedTasksNum.get() + 1 == getMaximumPoolSize()) {
//					// if (getMaximumPoolSize() == initialMaximumPoolSize.get()) {
//					// // Long val = totalDataRead.get() * 32;
//					// // log.debug(String.format(
//					// // "Special case where only 1 task is completed. Old value: %s, New value:
//					// %s",
//					// // totalDataRead.get(), val));
//					// // totalDataRead.set(val);
//					// }
//					//
//					// else {
//					// log.debug(String.format("Decrementing one task data read: %s B since this is
//					// an extra task",
//					// bytesRead));
//					// totalDataRead.getAndAdd(bytesRead * -1);
//					// }
//					// || submittedTasksNum.get() - 1 == getMaximumPoolSize()
//					// if (!checkTuning) {
//					// We have submitted [submittedTasksNum] tasks, and one of them is complete, so
//					// we should tune.
//					// isTuning.set(true);
//					// Tune
//					tune(r);
//					// isTuning.set(false);
//					// }
//					// else {
//					// log.debug("[WARNING]: We wanted to tune but there was already a thread
//					// tuning.");
//					// }
//				}
//
//			}
//		}
//
//		super.afterExecute(r, t);
//	}
//
//	public float getAverageDiskThroughput(String logPath) throws IOException {
//		float total = 0;
//
//		List<String> lines = readFileInListFromTo(logPath, currentLineNumberIoStat.get());
//		if (lines == null) {
//			return 0;
//		}
//		currentLineNumberIoStat.addAndGet(lines.size());
//		log.debug(String.format("currentLineNumberIoStat is now: %s", currentLineNumberIoStat));
//
//		if (lines.get(0).startsWith("Date"))
//			lines.remove(0);
//
//		for (String line : lines) {
//			total += getReadThroughputFromLine(line);
//		}
//
//		log.debug(String.format("total throughput: %s, size: %s", total, lines.size()));
//		float avg = total / lines.size();
//
//		return avg;
//	}
//
//	public float getTotalEpollWaitTime(String logPath) throws IOException {
//		float total = 0;
//
//		// RandomAccessFile raf = null;
//		//
//		// if (raf == null) {
//		// raf = new RandomAccessFile(logPath, "r");
//		// }
//		// log.debug("Seeking file to position: " + fileOffset);
//		// raf.seek(fileOffset);
//
//		List<String> lines = readFileInListFromTo(logPath, currentLineNumber.get());
//		if (lines == null) {
//			return 0;
//		}
//		currentLineNumber.addAndGet(lines.size());
//		log.debug(String.format("Current line number is now: %s", currentLineNumber));
//
//		for (String line : lines) {
//			total += getTimeFromLine(line);
//		}
//		// String line;
//		// while (line != null) {
//		// log.debug("line = " + line);
//		// fileOffset += line.getBytes().length + 1;
//		// total += getTimeFromLine(line);
//		// line = raf.readLine();
//		// }
//
//		return total;
//	}
//
//	public float getReadThroughputFromLine(String line) {
//		float result = 0;
//
//		result = Float.valueOf(line.split(",")[13]);
//
//		return result;
//	}
//
//	public float getTimeFromLine(String line) {
//		float result = 0;
//		String pattern = "<[\\d.+]*>";
//		Pattern p = Pattern.compile(pattern);
//		Matcher m = p.matcher(line);
//		if (m.find()) {
//			String value = m.group(0);
//			value = value.substring(1, value.length() - 1);
//			result = Float.parseFloat(value);
//		}
//
//		return result;
//	}
//
//	public void setThreadSize(int size) {
//		// Check whether we really need to change the pool size. If it is already there,
//		// we don't need to.
//		log.debug(String.format("Setting number of threads from %s to %s", getMaximumPoolSize(), size));
//		if (size == getMaximumPoolSize()) {
//			log.debug(String.format("Number of threads is already at %s, so no need to change", size));
//		} else {
//			setCorePoolSize(size);
//			setMaximumPoolSize(size);
//		}
//
//		int preStartCount = prestartAllCoreThreads();
//		log.debug(String.format("Prestarted %s threads!", preStartCount));
//	}
//
//	public void tune(Runnable command) {
//		long startTime = System.currentTimeMillis();
//		float total = 0;
//		int minThreadNumber = 2;
//		int maxThreadNumber = initialMaximumPoolSize.get();
//		TaskRunner taskRunner = (TaskRunner) command;
//		String straceFilePath = taskRunner.getStracePath();
//		String ioStatFilePath = taskRunner.getIoStatPath();
//
//		float avgDiskThroughput = 0;
//		Float totalTaskThroughput = 0F;
//
//		// straceFilePath = "/home/omranian/log.strace";
//
//		log.debug("Tuning number of threads...");
//
//		// if (getMaximumPoolSize() == minThreadNumber) {
//		// // We have reached the bottom; stop.
//		// log.debug(String.format("We have reached min number of threads, so
//		// reporting..."));
//		// report();
//		// setThreadSize(maxThreadNumber);
//		// tuningFinished.set(true);
//		// return;
//		// }
//
//		try {
//			// log.debug("Reading iostat file at location: " + ioStatFilePath);
//			// List<String> lines = readFileInList(ioStatFilePath);
//			// for (String line : lines) {
//			// log.debug("Line: " + line);
//			// }
//
//			avgDiskThroughput = getAverageDiskThroughput(ioStatFilePath);
//			log.debug(String.format("%s THREADS - disk throughput: %s", getCorePoolSize(), avgDiskThroughput));
//		}
//
//		catch (Exception e) {
//			// TODO: handle exception
//			e.printStackTrace();
//			log.debug("io exception = " + e.getMessage());
//		}
//
//		Float avgTaskThroughput = (float) calculateAverage(throughputs);
//		log.debug(String.format("%s THREADS - average task throughput: %s", getCorePoolSize(), avgTaskThroughput));
//
//		// Total task throughput
//		// bytes / ms
//		Long now = System.currentTimeMillis();
//		Long totalTime = (now - straceStartTime.get());
//		Float totalTimeFloat = totalTime.floatValue();
//		log.debug(String.format("%s THREADS - monitor start: %s, now: %s, diff = %s", getCorePoolSize(), straceStartTime.get(), now,
//				totalTimeFloat));
//
//		if (totalTime != 0) {
//			totalTaskThroughput = totalDataRead.floatValue() / (totalTimeFloat / 1000);
//			totalTaskThroughput = (totalTaskThroughput / 1024); // KB per second
//			totalTaskThroughput = (totalTaskThroughput / 1024); // MB per second
//			log.debug(String.format(
//					"%s THREADS - totalBytesRead = %s B, totalTime = %s ms, totalTaskThroughput = %s MBps",
//					getCorePoolSize(), totalDataRead, totalTimeFloat, totalTaskThroughput));
//		}
//
//		log.debug("Reading strace file at location: " + straceFilePath);
//
//		try {
//			total = getTotalEpollWaitTime(straceFilePath);
//			log.debug(String.format("%s THREADS - total epoll_wait time for in threadpool = %s", getCorePoolSize(),
//					total));
//			log.debug(String.format("%s THREADS - normalised epoll_wait time by number of threads = %s",
//					getCorePoolSize(), total / getCorePoolSize()));
//			long elapsedTime = TimeUnit.MILLISECONDS.toSeconds((System.currentTimeMillis() - straceStartTime.get()));
//			log.debug(String.format("%s THREADS - normalised epoll_wait time by elapsed time = %s ", getCorePoolSize(),
//					total / elapsedTime));
//			log.debug(String.format("%s THREADS - normalised both = %s ", getCorePoolSize(),
//					total / (getCorePoolSize() * elapsedTime)));
//
//			ArrayList<Float> values = new ArrayList<Float>(Arrays.asList(total / getCorePoolSize(), total / elapsedTime,
//					total / (getCorePoolSize() * elapsedTime), avgDiskThroughput, avgTaskThroughput,
//					totalTaskThroughput.floatValue()));
//			epolls.put(getCorePoolSize(), values);
//
//			// epolls_normal_by_time.put(getCorePoolSize(), total / elapsedTime);
//
//			if (total != 0) {
//				// Should we go up or go down?
//				int coreSize = initialMaximumPoolSize.get();
//				if (getMaximumPoolSize() == minThreadNumber) {
//					// We have reached the bottom. Double the thread number.
//					int newValue = (int) Math.floor(getMaximumPoolSize() * 2);
//					coreSize = Math.min(maxThreadNumber, newValue);
//					isMinReached.set(true);
//					// We have reached the bottom; stop.
//					log.debug(String.format("We have reached min number of threads, so reporting..."));
//					int optimal = report(taskRunner.getStageId());
//					setThreadSize(optimal);
//					tuningFinished.set(true);
//					return;
//				} else if (getMaximumPoolSize() == maxThreadNumber) {
//					// We have reached the top. Half the thread number.
//					int newValue = (int) Math.ceil(getMaximumPoolSize() / 2);
//					coreSize = Math.max(minThreadNumber, newValue);
//					isMinReached.set(false);
//				} else {
//					// We are between, go up or down.
//					if (isMinReached.get()) {
//						// Go up
//						int newValue = (int) Math.floor(getMaximumPoolSize() * 2);
//						coreSize = Math.min(maxThreadNumber, newValue);
//					} else {
//						// Go down
//						int newValue = (int) Math.ceil(getMaximumPoolSize() / 2);
//						coreSize = Math.max(minThreadNumber, newValue);
//					}
//				}
//
//				if (getMaximumPoolSize() == minThreadNumber) {
//					// We have reached the bottom; stop.
//				}
//
//				setThreadSize(coreSize);
//			}
//			long stopTime = System.currentTimeMillis();
//			long elapsedExecutionTime = stopTime - startTime;
//			log.debug(String.format("Tune execution time: %s ms", elapsedExecutionTime));
//		} catch (IOException ex) {
//			// TODO Auto-generated catch block
//			ex.printStackTrace();
//			log.debug("io exception = " + ex.getMessage());
//		}
//
//	}
//
//	private static int calculateAverage(List<Long> marks) {
//		long sum = 0;
//		for (Long mark : marks) {
//			sum += mark;
//		}
//		log.debug(String.format("Sum: %s, size: %s", sum, marks.size()));
//		return marks.isEmpty() ? 0 : (int) sum / marks.size();
//	}
//
//	private int report(int stageId) {
//		int optimal = initialMaximumPoolSize.get();
//		float minValue = 99999999;
//		float maxValue = 0;
//		System.err.println(String.format("=============== REPORT for stage %s ======================", stageId));
//		System.err.println(String.format(
//				"#cores, normalisedByThreadNum, normalisedByTime, normalisedByBoth, diskThroughput, avgTaskThroughput, totalTaskThroughput",
//				stageId));
//		for (Entry<Integer, ArrayList<Float>> entry : epolls.entrySet()) {
//			int numberOfThreads = entry.getKey();
//			ArrayList<Float> values = entry.getValue();
//			float normalisedByThreadNum = values.get(0);
//			float normalisedByTime = values.get(1);
//			float normalisedByBoth = values.get(2);
//			float diskThroughput = values.get(3);
//			float avgTaskThroughput = values.get(4);
//			float totalTaskThroughput = values.get(5);
//			System.err.println(String.format("%s,%s,%s,%s,%s,%s,%s", numberOfThreads, normalisedByThreadNum,
//					normalisedByTime, normalisedByBoth, diskThroughput, avgTaskThroughput, totalTaskThroughput));
//			//
//			// if (normalisedByBoth < minValue) {
//			// minValue = normalisedByBoth;
//			// optimal = numberOfThreads;
//			// }
//			if (totalTaskThroughput > maxValue) {
//				maxValue = totalTaskThroughput;
//				optimal = numberOfThreads;
//			}
//		}
//		System.err.println(String.format("Optimal number of cores = %s", optimal));
//		System.err.println("=============================================");
//
//		return optimal;
//	}
//
//	// private void report_old(int stageId) {
//	// int optimal = initialMaximumPoolSize.get();
//	// float minValue = 99999999;
//	// System.err.println(String.format("=============== REPORT for stage %s
//	// ======================", stageId));
//	// System.err.println(String.format(
//	// "=============== #cores, normalisedByThreadNum, normalisedByTime
//	// ======================", stageId));
//	// for (Entry<Integer, Pair<Float, Float>> entry : epolls.entrySet()) {
//	// int numberOfThreads = entry.getKey();
//	// Pair<Float, Float> values = entry.getValue();
//	// float normalisedByThreadNum = values.getLeft();
//	// float normalisedByTime = values.getRight();
//	// System.err.println(String.format("%s,%s,%s", numberOfThreads,
//	// normalisedByThreadNum, normalisedByTime));
//	//
//	// if (normalisedByThreadNum < minValue) {
//	// minValue = normalisedByThreadNum;
//	// optimal = numberOfThreads;
//	// }
//	// }
//	// System.err.println(String.format("Optimal number of cores = %s", optimal));
//	// System.err.println("=============================================");
//	// }
//
//	public void reset() {
//		setThreadSize(initialMaximumPoolSize.get());
//		submittedTasksNum.set(0);
//		finishedTasksNum.set(0);
//		tuningFinished.set(false);
//
//		throughputs = new ArrayList<Long>();
//
//		straceStartTime.set(System.currentTimeMillis());
//		currentLineNumber.set(readFileInList(straceFilePath).size());
//		currentLineNumberIoStat.set(readFileInList(ioStatFilePath).size());
//		log.debug(String.format("Starting monitoring strace at line: %s", currentLineNumber));
//		log.debug(String.format("Starting monitoring ioStat at line: %s", currentLineNumber));
//		// lastThreadPoolSize = initialMaximumPoolSize;
//
//	}
//
//	@Override
//	public void execute(Runnable command) {
//		if (command instanceof TaskRunner) {
//			TaskRunner taskRunner = (TaskRunner) command;
//			straceFilePath = taskRunner.getStracePath();
//			ioStatFilePath = taskRunner.getIoStatPath();
//			log.debug(String.format("Adding task %s to threadpool queue (i.e., execute())", taskRunner.taskId()));
//			// If the stage has changed, reset the threadpool to its initial state.
//			if (currentStage.get() != taskRunner.getStageId()) {
//				System.err.println(
//						String.format("Stage has changed from %s to %s. Resetting the threadpool to inital values...",
//								currentStage, taskRunner.getStageId()));
//				currentStage.set(taskRunner.getStageId());
//				reset();
//			}
//		}
//		super.execute(command);
//		// log.debug(
//		// String.format("submittedTasksNum = %s, corePoolSize = %s", submittedTasksNum,
//		// getCorePoolSize()));
//		// // If we have executed number of tasks equal to the thread pool size
//		//
//		// try {
//		// if (shouldTune) {
//		// log.debug("shouldTune flag has been set!, Tuning....");
//		// log.debug(
//		// String.format("Active threads = %s, Queue size = %s", getActiveCount(),
//		// getQueue().size()));
//		// tune(command);
//		// shouldTune = false;
//		// }
//		// } catch (Exception e) {
//		// log.debug("ERROR: Something went wrong in tuning");
//		// e.printStackTrace();
//		// }
//		// super.execute(command);
//		// ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
//		// long idBefore = Thread.currentThread().getId();
//		//
//		// long idAfter = Thread.currentThread().getId();
//		// assert (idBefore == idAfter);
//		//
//		// ThreadInfo threadInfo = threadMXBean.getThreadInfo(idAfter);
//
//		// log.debug(String.format("Thread [%s-%s] has spent %ss in WAITING
//		// state.", idBefore, idAfter, threadInfo.getWaitedTime()));
//
//	}
//
//}
