package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import java.util.LinkedList;
import java.util.Iterator;
import java.io.EOFException;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	
	public UserProcess() {
		// Moved the pageTable initialization to the load() function.
		
		fileDescriptorTable[0] = stdin;// standard input
		fileDescriptorTable[1] = stdout; // standard output
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		this.thread = new UThread(this);
		this.thread.setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// Extract virtual page number from virtual address
		int vpn = Processor.pageFromAddress(vaddr);
		
		// If the virtual page number is greater than the page table,
		// no data can be transferred. Return 0.
		if (vpn >= pageTable.length) return 0;

		if (!pageTable[vpn].valid) {
			// if virtual page number is not valid, get a new page assignment
			int newFreePage = UserKernel.getFreePage(this, vpn);
			// Set the physical page number in the page table to the newly acquired free page
			pageTable[vpn].ppn = newFreePage;
			// Set the valid bit to true
			pageTable[vpn].valid = true;
			if (!loadSections()) return 0;
			
		}
		
		int voff = Processor.offsetFromAddress(vaddr);
		TranslationEntry t = pageTable[vpn];
		t.used = true;
		int ppn = t.ppn;
		
		int paddr = Processor.makeAddress(ppn, voff);

		// Bounds check
		if (paddr < 0 || paddr >= memory.length)
			return 0;

		int amount = Math.min(length, memory.length - paddr);
		System.arraycopy(memory, paddr, data, offset, amount);

		return amount;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		// Extract virtual page number from virtual address
		int vpn = Processor.pageFromAddress(vaddr);
		
		// If the virtual page number is greater than the page table,
		// no data can be transferred. Return 0.
		if (vpn >= pageTable.length) return 0;

		if (!pageTable[vpn].valid) {
			// if virtual page number is not valid, get a new page assignment
			int newFreePage = UserKernel.getFreePage(this, vpn);
			// Set the physical page number in the page table to the newly acquired free page
			pageTable[vpn].ppn = newFreePage;
			// Set the valid bit to true
			pageTable[vpn].valid = true;
			
		}
		
		int voff = Processor.offsetFromAddress(vaddr);
		TranslationEntry t = pageTable[vpn];
		t.used = true;
		
		if (t.readOnly) return 0;
		
		int ppn = t.ppn;
		
		int paddr = Processor.makeAddress(ppn, voff);

		// for now, just assume that virtual addresses equal physical addresses
		if (paddr < 0 || paddr >= memory.length)
			return 0;

		int amount = Math.min(length, memory.length - paddr);
		System.arraycopy(data, offset, memory, paddr, amount);
		
		// Set the dirty bit to True
		t.dirty = true;

		return amount;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;
		
		this.pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++) {
			int ppn = UserKernel.getFreePage(this, i);
			pageTable[i] = new TranslationEntry(i, ppn, true, false, false, false);
		}

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				
				if (vpn >= pageTable.length) return false;
				int ppn = pageTable[vpn].ppn;
				
				if (!(i >= 0 && i < numPages)) return false;
				if (!(ppn >= 0 && ppn < Machine.processor().getNumPhysPages() )) return false;
				
				section.loadPage(i, ppn);
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for (int i = 0; i < numPages; i++) {
			TranslationEntry t = pageTable[i];
			UserKernel.addFreePage(t.ppn);
			t.valid = false;
		}
		coff.close();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	private static final int 
	syscallHalt = 0, 
	syscallExit = 1, 
	syscallExec = 2,
	syscallJoin = 3, 
	syscallCreate = 4, 
	syscallOpen = 5,
	syscallRead = 6, 
	syscallWrite = 7, 
	syscallClose = 8,
	syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		//System.out.println("System Call: " + Integer.toString(syscall));
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
            return handleRead(a0, a1, a2);
        case syscallWrite:
            return handleWrite(a0, a1, a2);	
        case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallExit:
			return handleExit(a0);
		case syscallJoin:
			return handleJoin(a0, a1);

		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}
	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			handleExit(0);
			Lib.assertNotReached("Unexpected exception");
		}
	}
	
	private int handleCreate(int vAddr) {
		return fileOpen(vAddr, true);
	}
	
	private int handleOpen(int vAddr) {
		return fileOpen(vAddr, false);
	}
	
	private int fileOpen(int vAddr, boolean create) {
		
		String filename = readVirtualMemoryString(vAddr, 256);
		
		if (filename == null) {
			System.out.println("Could not parse file name.");
			return -1;
		}
		
		// Search for available file descriptor
		for (int i = 0; i < 16; i++) {
			
			// If available file descriptor is found...
			if (fileDescriptorTable[i] == null) {
		
				// Search the global file open table to see if file is already open
				Iterator<OpenFileTableEntry> it = openFileTable.iterator();
				boolean fileAlreadyOpen = false;
				OpenFileTableEntry curEntry = null;
				
				while (it.hasNext()) {
					curEntry = it.next();
					if ( curEntry.getFileName().equals(filename) ) {
						fileAlreadyOpen = true;
						break;
					}			
				}
				
				// If file is already open, just increment file open count
				if (fileAlreadyOpen) {
					// If the file is marked for deletion, return with error immediately
					if (curEntry.isMarkedForDeletion()) {
						return -1;
					}
					curEntry.incrementOpenCount();
					fileDescriptorTable[i] = curEntry;
				}
				// Otherwise, create new entry in global file open table
				else {
					OpenFile newOpenedFile = ThreadedKernel.fileSystem.open(filename, create);
					
					if (newOpenedFile == null) {
						System.out.println("File could not be opened for some reason.");
						return -1;
					}
					
					OpenFileTableEntry newEntry = new OpenFileTableEntry(newOpenedFile);
					openFileTable.add(newEntry);
					fileDescriptorTable[i] = newEntry;
					
				}
				// Return the file descriptor for this file
				return i;
			}
		}
		// If this point is reached, then there are no available file descriptors.  Return error.
		System.out.println("No file descriptors available.");
		return -1;		
	}
	
	private int handleRead(int fileDescriptor, int bufferVAddr, int count){

		if (fileDescriptor < 0 || fileDescriptor > 15) {
			//System.out.println("fileDescriptor is invalid");
			return -1;
		}

		if (count < 0){
			//System.out.println("Count bytes is negative");
			return -1;
		}

		OpenFile file;

		if (fileDescriptorTable[fileDescriptor] == null){
			//System.out.println("File is not in the descriptor table");
			return -1;
		}else{
			file = fileDescriptorTable[fileDescriptor].getFileObject();
		}

		byte[] data_transf = new byte[count];
		int num_bytes_successfully_read = 0;
		num_bytes_successfully_read = file.read(data_transf, 0, count);

		if (num_bytes_successfully_read == -1) {
			//System.out.println("No bytes were read because of a fatal error");
			return -1;
		}

		int num_bytes_successfully_copied  = 0;
		num_bytes_successfully_copied = writeVirtualMemory(bufferVAddr, data_transf, 0, num_bytes_successfully_read);

		return num_bytes_successfully_copied;

	}

	private int handleWrite(int fileDescriptor, int bufferVAddr, int count) {

		if (fileDescriptor < 0 || fileDescriptor > 15) {
			//System.out.println("fileDescriptor is invalid");
			return -1;
		}

		if (count < 0) {
			//System.out.println("Count bytes is negative");
			return -1;	
		}

		OpenFile file;

		if (fileDescriptorTable[fileDescriptor] == null) {
			//System.out.println("File is not in the descriptor table");
			return -1;
		}else{
			file = fileDescriptorTable[fileDescriptor].getFileObject();
		}

		byte[] data_dest = new byte[count];
		int num_bytes_successfully_copied = 0;
		num_bytes_successfully_copied = readVirtualMemory(bufferVAddr, data_dest, 0, count);

		int num_bytes_successfully_written = 0;
		num_bytes_successfully_written = file.write(data_dest, 0, num_bytes_successfully_copied);

		if (num_bytes_successfully_written == -1 || num_bytes_successfully_written < count) {
			//System.out.println("No bytes were written because of a fatal error");
			return -1;
		}

		return num_bytes_successfully_written;
	}
	
	/** closes 1 out of the possible 16 files that are used by a processor **/
	private int handleClose(int fileIndx) {
		if (fileIndx < 0 || fileIndx > 15) {
			// file descriptor out of range
			return -1;
		}
		
		OpenFileTableEntry tableEntry = fileDescriptorTable[fileIndx];
		
		if (tableEntry == null) {
			// invalid file descriptor
			return -1;
		}
		
		fileDescriptorTable[fileIndx] = null;
		
//		Lib.assertTrue(tableEntry.getOpenCount() > 0);
		tableEntry.decrementOpenCount();

		// If this is the last process to have the file open
		if (tableEntry.getOpenCount() == 0) {
			String filename = tableEntry.getFileName();
			boolean isMarkedForDeletion = tableEntry.isMarkedForDeletion();
			
			// Close the Java file object
			tableEntry.getFileObject().close();
			
			// Remove file from global open file table
			openFileTable.remove(tableEntry);
			
			// If some process wanted to delete this file, delete it now
			if (isMarkedForDeletion) {
				if (ThreadedKernel.fileSystem.remove(filename)) {
					return 0;
				}
				else {
					return -1;
				}
			}
		}
		
		return 0;

	}
	/** deletes a file as long as no one is using it. If file is in use,
	 *  then deletion is prolonged, and other users who wish to use the file after
	 *  the call is made cannot use the file until after deletion.
	 */
	private int handleUnlink(int vAddr) {
		String filename = readVirtualMemoryString(vAddr, 256);
		
		if (filename == null) {
			System.out.println("Could not parse file name.");
			return -1;
		}
		
		// Search for file to be deleted in per-process file descriptor table, close
		// all file descriptors referring to the file,  and mark file for deletion.
		boolean fileInFDTable = false;
		for(int i = 0; i < fileDescriptorTable.length; i++) {
			if (fileDescriptorTable[i] != null) {
				OpenFileTableEntry curEntry = fileDescriptorTable[i];
				if (filename.equals(curEntry.getFileName())) {
					curEntry.markForDeletion();
					handleClose(i);
					fileInFDTable = true;
					/*
					 * There is no break statement here since a process can have multiple file
					 * descriptors for the same file.
					 * 
					 * Also, there is no need to call StubFileSystem.remove() since handleClose()
					 * will handle deleting the file if this is the last process to have the file
					 * open.
					 */
				}
			}
		}
		
		// If file is not in per-process file descriptor table, search for file in
		// global open file table.
		if (!fileInFDTable) {
			Iterator<OpenFileTableEntry> it = openFileTable.iterator();
			boolean fileStillOpen = false;
			OpenFileTableEntry curEntry = null;
			
			while (it.hasNext()) {
				curEntry = it.next();
				if (filename.equals( curEntry.getFileName())) {
					fileStillOpen = true;
					break;
				}			
			}
			
			// If file is found in open file table, another process still has the file open.
			// Do not attempt to delete, just mark file for deletion.
			if(fileStillOpen) {
				curEntry.markForDeletion();
			}
			// If file is not found in open file table, the file has already been closed.
			// Simply delete the file immediately by name.
			else {
				if (ThreadedKernel.fileSystem.remove(filename)) {
					return 0;
				}
				else {
					return -1;
				}
			}
		}
		
		return 0;
	}
	
	private int handleExit(int a0) {
		// TODO
		return 0;
	}
	
	private int handleExec(int a0, int a1, int a2) {
		// TODO
		return 0;
	}

	private int handleJoin(int a0, int a1) {
		// TODO
		return 0;
	}

	
	
	protected static class OpenFileTableEntry {
		OpenFileTableEntry(OpenFile file) {
			this.file = file;
			fileOpenCount++;
			filename = file.getName();
		}
		
		public OpenFile getFileObject() {
			return file;
		}
		
		public String getFileName() {
			return filename;
		}
		
		public void incrementOpenCount() {
			fileOpenCount++;
		}
		
		public void decrementOpenCount() {
			fileOpenCount--;
		}
		
		public int getOpenCount() {
			return fileOpenCount;
		}
		
		public void markForDeletion() {
			markedForDeletion = true;
		}
		
		public boolean isMarkedForDeletion() {
			return markedForDeletion;
		}
		
		private OpenFile file;
		private String filename;
		private int fileOpenCount = 0;
		private boolean markedForDeletion = false;
	} // end OpenFileTableEntry class
	
	
	
	// system wide open file table
	protected static LinkedList<OpenFileTableEntry> openFileTable = new LinkedList<OpenFileTableEntry>();
	
	protected static OpenFileTableEntry stdin = new OpenFileTableEntry(UserKernel.console.openForReading()); // standard input
	protected static OpenFileTableEntry stdout = new OpenFileTableEntry(UserKernel.console.openForWriting());
	
	/** Array of open file objects */
	protected OpenFileTableEntry[] fileDescriptorTable = new OpenFileTableEntry[16];
	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;
	private String name;
	private UThread thread;
	
	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';
	

}
